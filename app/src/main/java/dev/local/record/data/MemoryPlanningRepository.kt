package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.ai.AutomaticMemoryPolicy
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryPlanningEvent
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryPlanningTask
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import dev.local.record.domain.currentAt
import dev.local.record.domain.evolveMemoryPlanning
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable, deletable provenance. Credentials and copies of source text are never stored here. */
@Serializable
data class MemoryPlanningInput(
    val sourceContentId: String,
    val memoryRevision: Long,
    val memories: List<MemoryReference>,
    val connection: AiConnection,
    val binding: CapabilityBinding,
    val sourceZoneId: String? = null,
    val sourceObservedAt: Long? = null,
    val automatic: Boolean = false,
    val historyAuthorized: Boolean = false,
    val maxSourceChars: Int = 4000,
    val maxCalls: Int = 1
)

/** Event-first planning commands. This repository cannot call a provider or schedule work. */
class MemoryPlanningRepository(private val db: RecordDatabase) {
    private val dao = db.memoryPlanning()
    val tasks = dao.observe().map { rows -> rows.map(MemoryPlanningRow::domain) }

    suspend fun task(id: String) = dao.get(id)?.domain()
    suspend fun requested(): List<MemoryPlanningTask> = dao.all().filter { it.status == "REQUESTED" }.map(MemoryPlanningRow::domain).sortedBy { it.createdAt }
    suspend fun input(task: MemoryPlanningTask): MemoryPlanningInput? = db.processing().content(task.requestContentId)?.body?.let { eventJson.decodeFromString(it) }

    suspend fun request(turnId: String, connection: AiConnection, binding: CapabilityBinding, memories: List<MemoryReference>, now: Long, automatic: Boolean = false, historyAuthorized: Boolean = false): MemoryPlanningTask = db.withTransaction {
        require(!automatic || !historyAuthorized)
        require(binding.capability == AiCapability.MEMORY && binding.connectionId == connection.id && binding.model.isNotBlank() && connection.supports(AiCapability.MEMORY)) { "记忆模型配置不兼容" }
        require(memories.size <= 10 && memories.distinctBy { it.id }.size == memories.size) { "记忆引用超限或重复" }
        // Automatic callbacks never retry uncertain or rejected attempts. Manual retries are explicit.
        val existing = dao.all().map(MemoryPlanningRow::domain).lastOrNull { it.turnId == turnId && (automatic || historyAuthorized || it.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING, MemoryPlanningStatus.COMPLETED)) }
        if (existing != null) return@withTransaction existing
        val turn = requireNotNull(db.conversations().turn(turnId)).domain()
        require(turn.status == TurnStatus.ANSWERED && db.conversations().conversation(turn.conversationId)?.deleted == false) { "请先完成对话回复" }
        require(turn.userText.length <= 4000 && HistorySearchRepository(db).read(turn.userContentId) != null) { "来源已变化或超过4000字上限" }
        require(db.processing().memories().none { it.sourceTurnId == turnId && it.status in setOf("FORGOTTEN", "INVALIDATED", "MERGED") }) { "此消息已有忘记或失效的记忆；如需重新记住，请手动保存" }
        val id = "memory-plan:${UUID.randomUUID()}"
        val contentId = "$id:input"
        val sourceEvent = db.recordings().events().firstOrNull { it.aggregateId == turnId && it.eventType == "AssistantTurnRequested" }
        val zoneId = sourceEvent?.let { (eventJson.decodeFromString<dev.local.record.domain.TurnEvent>(it.payload) as dev.local.record.domain.TurnEvent.Requested).sourceZoneId }
        val input = MemoryPlanningInput(turn.userContentId, db.recordings().memoryRevision(), memories, connection, binding, zoneId, db.processing().content(turn.userContentId)?.createdAt, automatic, historyAuthorized)
        db.processing().saveContent(ContentRow(contentId, "memory-planning-input", eventJson.encodeToString(input), now))
        commit(MemoryPlanningTask(id), MemoryPlanningEvent.Requested(turnId, contentId, now, automatic), now)
    }

    /** One atomic finite authorization. A repeated selected source never buys another attempt. */
    suspend fun requestHistory(turnIds: List<String>, connection: AiConnection, binding: CapabilityBinding, memoriesByTurn: Map<String, List<MemoryReference>>, now: Long): Int = db.withTransaction {
        val selected = turnIds.distinct()
        require(selected.isNotEmpty() && selected.size <= 10) { "每批请选择1至10条消息" }
        val before = dao.all().map { it.id }.toSet()
        selected.map { request(it, connection, binding, memoriesByTurn[it].orEmpty(), now, historyAuthorized = true) }.count { it.id !in before }
    }

    /** Recheck sources and targets before uploading, then freeze the current knowledge revision. */
    suspend fun start(id: String, now: Long): MemoryPlanningInput? = db.withTransaction {
        var current = task(id) ?: return@withTransaction null
        if (current.status != MemoryPlanningStatus.REQUESTED) return@withTransaction null
        var input = input(current)
        if (input == null || !valid(current, input, checkRevision = false)) {
            commit(current, MemoryPlanningEvent.Failed("SOURCE_CHANGED"), now)
            return@withTransaction null
        }
        if (input.automatic && automaticAttempts(now) >= 20) {
            commit(current, MemoryPlanningEvent.Failed("DAILY_LIMIT"), now)
            return@withTransaction null
        }
        // Queued sources can follow unrelated additions, but a running snapshot is never refreshed.
        val revision = db.recordings().memoryRevision()
        if (input.memoryRevision != revision) {
            input = input.copy(memoryRevision = revision)
            val contentId = "$id:input:${UUID.randomUUID()}"
            db.processing().saveContent(ContentRow(contentId, "memory-planning-input", eventJson.encodeToString(input), now))
            val priorContentId = current.requestContentId
            current = commit(current, MemoryPlanningEvent.SnapshotUpdated(contentId), now)
            db.processing().deleteContent(priorContentId)
        }
        commit(current, MemoryPlanningEvent.Started, now)
        input
    }

    suspend fun isCurrent(id: String): Boolean = db.withTransaction {
        val current = task(id) ?: return@withTransaction false
        val input = input(current) ?: return@withTransaction false
        current.status == MemoryPlanningStatus.RUNNING && valid(current, input)
    }

    suspend fun complete(id: String, items: List<MemoryDraft>, now: Long, autoConfirm: Boolean = false): Boolean = db.withTransaction {
        val current = task(id) ?: return@withTransaction false
        if (current.status != MemoryPlanningStatus.RUNNING) return@withTransaction false
        val input = input(current)
        if (input == null || !valid(current, input)) {
            commit(current, MemoryPlanningEvent.Failed("SOURCE_CHANGED"), now)
            return@withTransaction false
        }
        val turn = requireNotNull(db.conversations().turn(current.turnId)).domain()
        require(items.size <= 3 && items.all { it.text.isNotBlank() && it.text.length <= 500 && it.evidence.isNotBlank() && it.evidence.length <= 200 && turn.userText.contains(it.evidence) }) { "记忆证据不匹配" }
        val processing = ProcessingRepository(db)
        val beforeIds = processing.memories().map { it.id }.toSet()
        val knowledge = MemoryKnowledgeRepository(db)
        val prepared = knowledge.prepare(current.turnId, items, input.memories.map { it.id }.toSet(), input.sourceZoneId)
        processing.proposeConversationMemories(current.turnId, prepared, now)
        val created = processing.memories().filter { it.id !in beforeIds && it.sourceTurnId == current.turnId }
        if (autoConfirm) {
            created.forEach { candidate ->
                val index = prepared.indexOfFirst { it.text == candidate.text && it.evidence == candidate.evidence }
                val fact = candidate.fact
                val memories = processing.memories()
                if (index >= 0 && candidate.status == MemoryStatus.CANDIDATE && candidate.change?.action == MemoryAction.ADD && fact != null &&
                    AutomaticMemoryPolicy.eligible(items[index], turn.userText) &&
                    memories.none { it.suppressionKey == fact.key || it.status == MemoryStatus.CONFIRMED && it.fact?.key == fact.key }
                ) {
                    knowledge.confirm(candidate.id, now)
                }
            }
        }
        commit(current, MemoryPlanningEvent.Completed(created.size), now)
        true
    }

    suspend fun fail(id: String, reason: String, now: Long) = db.withTransaction {
        val current = task(id) ?: return@withTransaction
        if (current.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING)) commit(current, MemoryPlanningEvent.Failed(reason), now)
    }

    suspend fun cancel(id: String, now: Long) = db.withTransaction {
        val current = task(id) ?: return@withTransaction
        if (current.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING)) commit(current, MemoryPlanningEvent.Cancelled, now)
    }

    /** Revoking background authorization leaves manually requested tasks untouched. */
    suspend fun cancelAutomatic(now: Long) = db.withTransaction {
        dao.all().map(MemoryPlanningRow::domain).forEach { current ->
            if (current.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING) && input(current)?.automatic == true) {
                commit(current, MemoryPlanningEvent.Cancelled, now)
            }
        }
    }

    suspend fun recoverInterrupted(now: Long) {
        dao.all().filter { it.status == "RUNNING" }.forEach { fail(it.id, "INTERRUPTED", now) }
    }

    /** Forgetting invalidates all snapshots; they carry a global knowledge revision. */
    suspend fun invalidateSnapshots(now: Long) = db.withTransaction {
        dao.all().forEach { row ->
            if (row.status in setOf("REQUESTED", "RUNNING")) commit(row.domain(), MemoryPlanningEvent.Failed("SOURCE_CHANGED"), now)
            db.processing().deleteContent(row.requestContentId)
        }
    }

    suspend fun deleteConversation(conversationId: String, now: Long) = db.withTransaction {
        val turnIds = db.conversations().turns(conversationId).map { it.id }.toSet()
        dao.all().filter { it.turnId in turnIds }.forEach { row ->
            cancel(row.id, now)
            db.processing().deleteContent(row.requestContentId)
        }
    }

    suspend fun replay() = db.withTransaction {
        val states = linkedMapOf<String, MemoryPlanningTask>()
        db.recordings().events().filter { it.aggregateType == "MemoryPlanning" }.forEach { row ->
            require(row.schemaVersion == 1)
            val current = states[row.aggregateId] ?: MemoryPlanningTask(row.aggregateId)
            check(row.aggregateVersion == current.version + 1)
            states[row.aggregateId] = evolveMemoryPlanning(current, eventJson.decodeFromString<MemoryPlanningEvent>(row.payload))
        }
        dao.clear()
        states.values.forEach { dao.save(MemoryPlanningRow.from(it)) }
    }

    private suspend fun valid(task: MemoryPlanningTask, input: MemoryPlanningInput, checkRevision: Boolean = true): Boolean {
        val turn = db.conversations().turn(task.turnId)?.domain() ?: return false
        return turn.status == TurnStatus.ANSWERED && turn.userContentId == input.sourceContentId &&
            db.conversations().conversation(turn.conversationId)?.deleted == false &&
            db.processing().content(input.sourceContentId)?.body == turn.userText &&
            turn.userText.length <= input.maxSourceChars && input.maxSourceChars in 1..4000 && input.maxCalls == 1 &&
            HistorySearchRepository(db).read(input.sourceContentId) != null &&
            (!checkRevision || db.recordings().memoryRevision() == input.memoryRevision) && input.memories.all { ref ->
                val memory = db.processing().memory(ref.id)?.domain()
                memory?.version == ref.version && memory.currentAt(System.currentTimeMillis())
            }
    }

    /** UTC calendar days; STARTED is counted even if the result is lost or input is later erased. */
    private suspend fun automaticAttempts(now: Long): Int {
        val events = db.recordings().events()
        val automaticIds = events.filter { it.eventType == "MemoryPlanningRequested" }.filter {
            (eventJson.decodeFromString<MemoryPlanningEvent>(it.payload) as MemoryPlanningEvent.Requested).automatic
        }.map { it.aggregateId }.toSet()
        val day = Math.floorDiv(now, 86_400_000L)
        return events.count { it.eventType == "MemoryPlanningStarted" && it.aggregateId in automaticIds && Math.floorDiv(it.occurredAt, 86_400_000L) == day }
    }

    private suspend fun commit(current: MemoryPlanningTask, event: MemoryPlanningEvent, now: Long): MemoryPlanningTask {
        check(current.version == (db.recordings().version(current.id) ?: 0))
        val next = evolveMemoryPlanning(current, event)
        val payload = eventJson.encodeToString<MemoryPlanningEvent>(event)
        val commandId = "${current.id}:${next.version}"
        val position = db.recordings().insert(EventRow(eventId = UUID.randomUUID().toString(), aggregateType = "MemoryPlanning", aggregateId = current.id, aggregateVersion = next.version, eventType = eventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content, payload = payload, occurredAt = now, recordedAt = now, correlationId = current.turnId, causationId = commandId, commandId = commandId))
        dao.save(MemoryPlanningRow.from(next))
        db.recordings().checkpoint(ProjectionCheckpoint(position = position))
        return next
    }
}
