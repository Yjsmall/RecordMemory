package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryEvent
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryPredicate
import dev.local.record.domain.MemorySource
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.confirmable
import dev.local.record.domain.memoryFingerprint
import dev.local.record.domain.validateMemoryFact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Validates plans and source dependencies. All operations are local, event-first transactions. */
internal class MemoryKnowledgeRepository(private val db: RecordDatabase) {
    private val processing = ProcessingRepository(db)

    suspend fun prepare(turnId: String, drafts: List<MemoryDraft>, allowedTargets: Set<String>, zoneId: String?): List<MemoryDraft> {
        val turn = requireNotNull(db.conversations().turn(turnId)).domain()
        val source = requireNotNull(db.processing().content(turn.userContentId))
        val existing = processing.memories()
        return drafts.map { draft ->
            val change = draft.change ?: MemoryChange()
            require(change.question.length <= 300)
            require(change.action != MemoryAction.ASK_USER || change.question.isNotBlank()) { "澄清问题不能为空" }
            val fact = draft.fact?.also {
                validateMemoryFact(it)
                require(it.sources.isEmpty()) { "模型不能指定来源记录" }
                require(it.zoneId == null) { "事实时区由来源确定" }
                require(it.subject == "self" && draft.evidence.contains("我") || it.subject != "self" && draft.evidence.contains(it.subject)) { "主体缺少明确证据" }
                require(it.subject != "self" || !Regex("妈妈|爸爸|母亲|父亲|妻子|丈夫|朋友|同事|哥哥|姐姐|弟弟|妹妹|儿子|女儿|[他她]").containsMatchIn(draft.evidence)) { "多个或其他主体需要单独澄清" }
                require(it.subject == "self" || draft.text.contains(it.subject)) { "正文必须保留其他主体" }
                require(it.scope.isEmpty() || draft.evidence.contains(it.scope)) { "范围缺少明确证据" }
                listOfNotNull(it.validFrom, it.validUntil).forEach { date -> requireDateEvidence(date, draft.evidence, source.createdAt, zoneId) }
            }?.copy(
                zoneId = zoneId,
                sources = listOf(
                    MemorySource(
                        contentId = source.id,
                        conversationId = turn.conversationId,
                        turnId = turn.id,
                        evidence = draft.evidence,
                        start = turn.userText.indexOf(draft.evidence),
                        end = turn.userText.indexOf(draft.evidence) + draft.evidence.length,
                        observedAt = source.createdAt,
                        zoneId = zoneId
                    )
                )
            )
            if (change.targetId != null) {
                require(change.targetId in allowedTargets) { "目标不在本轮记忆范围" }
                val target = requireNotNull(processing.memory(change.targetId))
                require(target.status == MemoryStatus.CONFIRMED && target.version == change.expectedVersion) { "目标记忆已变化" }
                require(fact != null && target.fact?.key == fact.key) { "不能跨主体或范围更新记忆" }
                if (change.action == MemoryAction.REINFORCE) require(draft.text == target.text && sameValidity(fact, target.fact)) { "补充依据不能改变旧事实" }
            } else {
                require(change.action in setOf(MemoryAction.ADD, MemoryAction.ASK_USER) && change.expectedVersion == null) { "更新缺少目标版本" }
            }
            val conflict = existing.firstOrNull { it.status == MemoryStatus.CONFIRMED && fact != null && it.fact?.key == fact.key }
            val safeChange = if (change.action == MemoryAction.ADD && conflict != null) {
                MemoryChange(MemoryAction.ASK_USER, conflict.id, conflict.version, "此范围已有记忆。请核对旧事实后编辑确认，或重新整理为明确的替代方案。")
            } else {
                change
            }
            draft.copy(fact = fact, change = safeChange)
        }
    }

    suspend fun confirm(id: String, now: Long) = db.withTransaction {
        val candidate = requireNotNull(processing.memory(id))
        if (candidate.status == MemoryStatus.CONFIRMED || candidate.status == MemoryStatus.MERGED) return@withTransaction
        require(candidate.confirmable()) { "请先澄清并编辑这条候选" }
        candidate.fact?.let { fact ->
            require(processing.memories().none { it.suppressionKey == fact.key }) { "此范围已被忘记" }
            require(fact.sources.isNotEmpty() && fact.sources.all { validSource(it) }) { "候选来源已变化" }
        }
        val change = candidate.change ?: MemoryChange()
        val target = change.targetId?.let { requireNotNull(processing.memory(it)) }
        if (target != null) {
            require(target.status == MemoryStatus.CONFIRMED && target.version == change.expectedVersion) { "旧记忆已变化，请重新整理" }
            require(candidate.fact != null && candidate.fact.key == target.fact?.key)
            require(requireNotNull(target.fact).sources.all { validSource(it) }) { "旧记忆来源已变化" }
        }
        when (change.action) {
            MemoryAction.ADD -> {
                require(candidate.fact == null || processing.memories().none { it.status == MemoryStatus.CONFIRMED && it.fact?.key == candidate.fact.key }) { "已有同范围事实，请先处理冲突" }
                commit(candidate, MemoryEvent.Confirmed, now)
            }
            MemoryAction.SUPERSEDE -> {
                val fact = requireNotNull(candidate.fact)
                val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(fact.zoneId ?: "UTC")).toLocalDate()
                require(fact.validFrom == null || !date.isBefore(LocalDate.parse(fact.validFrom))) { "替代尚未生效，请在生效日期后确认" }
                commit(requireNotNull(target), MemoryEvent.Superseded(id), now)
                commit(candidate, MemoryEvent.Confirmed, now)
                ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(target.id), now = now)
            }
            MemoryAction.REINFORCE -> {
                val current = requireNotNull(target)
                require(candidate.text == current.text && sameValidity(candidate.fact, current.fact))
                val combined = requireNotNull(current.fact).copy(sources = (current.fact.sources + requireNotNull(candidate.fact).sources).distinctBy { it.contentId })
                require(combined.sources.size <= 20) { "来源数量达到上限" }
                update(current, combined, now)
                processing.terminalMemory(id, now, "reinforced") { MemoryEvent.Merged(current.id, it) }
            }
            MemoryAction.ASK_USER -> error("请先澄清")
        }
    }

    /** Source withdrawal rewrites surviving evidence and erases all older copies containing it. */
    suspend fun withdrawSources(now: Long, withdrawn: (MemorySource) -> Boolean) = db.withTransaction {
        processing.memories().filter { it.visible || it.status == MemoryStatus.SUPERSEDED }.forEach { item ->
            val fact = item.fact ?: return@forEach
            if (fact.sources.none(withdrawn)) return@forEach
            val surviving = fact.sources.filterNot(withdrawn).filter { validSource(it) }
            if (surviving.isEmpty()) {
                processing.terminalMemory(item.id, now, "sources-withdrawn") { MemoryEvent.Invalidated(it) }
            } else {
                val updated = update(item, fact.copy(sources = surviving), now)
                processing.purgeHistoryContent(item.sourceRecordingId, item.id, updated.contentId)
                ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(item.id), now = now)
            }
        }
    }

    suspend fun forget(id: String, now: Long) = db.withTransaction {
        val item = requireNotNull(processing.memory(id))
        if (!item.visible && item.status != MemoryStatus.SUPERSEDED) return@withTransaction
        val key = item.fact?.key.orEmpty()
        val label = item.fact?.let { fact -> listOf(if (fact.subject == "self") "我" else fact.subject, MemoryPredicate.entries.first { it.key == fact.predicate }.label, fact.scope).filter { it.isNotBlank() }.joinToString(" · ") }.orEmpty()
        val suppressionContentId = if (key.isNotEmpty()) UUID.randomUUID().toString() else ""
        if (suppressionContentId.isNotEmpty()) db.processing().saveContent(ContentRow(suppressionContentId, "memory-suppression", memoryBody(label, ""), now))
        val affected = processing.memories().filter { it.id == id || key.isNotEmpty() && it.fact?.key == key || it.change?.targetId == id }
        affected.filter { it.visible || it.status == MemoryStatus.SUPERSEDED }.forEach { memory ->
            processing.terminalMemory(memory.id, now, "forget") { MemoryEvent.Forgotten(it, key, suppressionContentId) }
        }
    }

    /** Explicit UI authorization only. It permits new learning and never restores deleted bodies. */
    suspend fun allowRelearning(id: String, now: Long) = db.withTransaction {
        val key = requireNotNull(processing.memory(id)).suppressionKey
        if (key.isBlank()) return@withTransaction
        val affected = processing.memories().filter { it.status == MemoryStatus.FORGOTTEN && it.suppressionKey == key }
        affected.forEach { commit(it, MemoryEvent.RelearningAllowed, now) }
        db.recordings().events().filter { it.aggregateId in affected.map { item -> item.id } && it.eventType == "MemoryForgotten" }.forEach { row ->
            val event = eventJson.decodeFromString<MemoryEvent>(row.payload) as MemoryEvent.Forgotten
            if (event.suppressionContentId.isNotBlank()) db.processing().deleteContent(event.suppressionContentId)
        }
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    private suspend fun validSource(source: MemorySource): Boolean {
        val body = db.processing().content(source.contentId)?.body ?: return false
        if (source.origin == "USER_CORRECTION") return eventJson.decodeFromString<MemoryBody>(body).text == source.evidence
        if (source.start < 0 || source.end > body.length || source.start >= source.end || body.substring(source.start, source.end) != source.evidence) return false
        return if (source.turnId.isNotEmpty()) {
            val turn = db.conversations().turn(source.turnId)
            turn?.userContentId == source.contentId && db.conversations().conversation(source.conversationId)?.deleted == false
        } else {
            db.recordings().get(source.recordingId)?.status != "DELETED" && db.processing().text(source.recordingId)?.transcriptContentId == source.contentId
        }
    }

    private suspend fun update(item: MemoryItem, fact: MemoryFact, now: Long): MemoryItem {
        val contentId = UUID.randomUUID().toString()
        val evidence = fact.sources.first().evidence
        db.processing().saveContent(ContentRow(contentId, "memory", memoryBody(item.text, evidence, fact, item.change), now))
        return processing.commitMemory(item.id, MemoryEvent.KnowledgeUpdated(contentId), "${item.id}:knowledge:${item.version}", now, item.text, evidence)
    }

    private suspend fun commit(item: MemoryItem, event: MemoryEvent, now: Long) = processing.commitMemory(item.id, event, "${item.id}:knowledge:${item.version}", now, null, null)
}

private fun sameValidity(first: MemoryFact?, second: MemoryFact?) = first?.validFrom == second?.validFrom && first?.validUntil == second?.validUntil

private fun requireDateEvidence(value: String, evidence: String, observedAt: Long, zoneId: String?) {
    val date = LocalDate.parse(value)
    val observed = zoneId?.let { Instant.ofEpochMilli(observedAt).atZone(ZoneId.of(it)).toLocalDate() }
    require(
        evidence.contains(value) || evidence.contains("${date.year}年${date.monthValue}月${date.dayOfMonth}日") ||
            observed != null && listOf("今天" to observed, "明天" to observed.plusDays(1), "昨天" to observed.minusDays(1)).any { (word, day) -> evidence.contains(word) && date == day }
    ) { "日期缺少明确证据" }
}
