package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.AiJob
import dev.local.record.domain.AiJobEvent
import dev.local.record.domain.JobStatus
import dev.local.record.domain.MemoryEvent
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.RecordingText
import dev.local.record.domain.TextEvent
import dev.local.record.domain.TextOrigin
import dev.local.record.domain.duplicatesMemory
import dev.local.record.domain.evolveJob
import dev.local.record.domain.evolveMemory
import dev.local.record.domain.evolveText
import dev.local.record.domain.memoryFingerprint
import dev.local.record.domain.summaryEventFor
import dev.local.record.domain.titleEventFor
import dev.local.record.domain.validateJob
import dev.local.record.domain.validateMemory
import dev.local.record.domain.validateText
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MemoryDraft(val type: MemoryKind, val text: String, val evidence: String)

sealed interface StoredResult {
    data class Transcript(val text: String) : StoredResult
    data class Title(val text: String) : StoredResult
    data class Summary(val text: String) : StoredResult
    data class Memories(val items: List<MemoryDraft>) : StoredResult
}

data class FollowUp(val id: String, val capability: String, val generation: Int)

data class Claim(val job: AiJob, val attempt: Int)

/** AI and memory writes share the recording event log and commit projections in the same transaction. */
class ProcessingRepository(private val db: RecordDatabase) {
    private val events = db.recordings()
    private val dao = db.processing()
    val texts = dao.observeTexts().map { rows -> rows.map(RecordingTextRow::domain) }
    val jobs = dao.observeJobs().map { rows -> rows.map(AiJobRow::domain) }
    val memories = dao.observeMemories().map { rows -> rows.map(MemoryRow::domain) }

    suspend fun text(recordingId: String) = dao.text(recordingId)?.domain() ?: RecordingText(recordingId)
    suspend fun job(id: String) = dao.job(id)?.domain()
    suspend fun jobsFor(recordingId: String) = dao.jobsFor(recordingId).map(AiJobRow::domain)
    suspend fun memories() = dao.memories().map(MemoryRow::domain)
    suspend fun memory(id: String) = dao.memory(id)?.domain()

    suspend fun proposeConversationMemories(turnId: String, items: List<MemoryDraft>, now: Long, explicit: Boolean = false) = db.withTransaction {
        val turn = requireNotNull(db.conversations().turn(turnId)).domain()
        require(turn.status != dev.local.record.domain.TurnStatus.DELETED && db.conversations().conversation(turn.conversationId)?.deleted == false)
        val existing = memories()
        items.distinctBy { memoryFingerprint(it.text) }.take(3).forEach { item ->
            require(item.text.isNotBlank() && item.text.length <= 500)
            if (!explicit && (item.evidence.isBlank() || !turn.userText.contains(item.evidence))) return@forEach
            val fingerprint = memoryFingerprint(item.text)
            if (explicit) {
                val candidate = existing.firstOrNull { it.status == MemoryStatus.CANDIDATE && memoryFingerprint(it.text) == fingerprint }
                if (candidate != null) {
                    commitMemory(candidate.id, MemoryEvent.Confirmed, "${candidate.id}:confirm", now, null, null)
                    return@forEach
                }
            }
            if (existing.any { (it.visible || !explicit) && (it.fingerprint == fingerprint || it.text.isNotBlank() && memoryFingerprint(it.text) == fingerprint) }) return@forEach
            val id = UUID.randomUUID().toString()
            val contentId = UUID.randomUUID().toString()
            dao.saveContent(ContentRow(contentId, "memory", memoryBody(item.text, item.evidence), now))
            commitMemory(id, MemoryEvent.FromConversation(item.type.name, contentId, turn.conversationId, turn.id, turn.userContentId), "$id:propose", now, item.text, item.evidence)
            if (explicit) commitMemory(id, MemoryEvent.Confirmed, "$id:confirm", now, null, null)
        }
        if (explicit) MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    suspend fun onConversationDeleted(conversationId: String, now: Long) = db.withTransaction {
        MemoryPlanningRepository(db).deleteConversation(conversationId, now)
        memories().filter { it.sourceConversationId == conversationId && it.visible }.forEach { memory ->
            commitMemory(memory.id, MemoryEvent.Invalidated(memoryFingerprint(memory.text)), "${memory.id}:conversation-deleted", now, null, null)
            purgeHistoryContent("", memory.id)
            ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(memory.id), now = now)
        }
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    suspend fun releaseLeases() = dao.clearLeases()

    suspend fun hasPendingWork() = dao.pending().isNotEmpty()

    suspend fun enqueue(id: String, recordingId: String, capability: String, generation: Int, sourceContentId: String?, now: Long): AiJob = db.withTransaction {
        val job = commitJob(id, AiJobEvent.Requested(recordingId, capability, generation, sourceContentId), "$id:request", now, 0)
        if (job.status == JobStatus.REQUESTED || job.status == JobStatus.RUNNING) dao.saveOutbox(OutboxRow(id, "PENDING"))
        job
    }

    suspend fun claim(now: Long, leaseUntil: Long): Claim? = db.withTransaction {
        val pending = dao.pending()
        for (row in pending) {
            val current = dao.job(row.jobId)?.domain() ?: continue
            val lease = dao.job(row.jobId)?.leaseUntil ?: 0
            if (lease > now) continue
            if (current.status != JobStatus.REQUESTED && current.status != JobStatus.RUNNING) {
                dao.saveOutbox(OutboxRow(current.id, "DONE"))
                continue
            }
            val attempt = current.attempt + 1
            val job = commitJob(current.id, AiJobEvent.AttemptStarted(attempt), "${current.id}:attempt:$attempt", now, leaseUntil)
            return@withTransaction Claim(job, attempt)
        }
        null
    }

    suspend fun applySuccess(jobId: String, attempt: Int, result: StoredResult, followUps: List<FollowUp>, now: Long) = db.withTransaction {
        val job = requireNotNull(dao.job(jobId)).domain()
        if (events.command("$jobId:complete:$attempt") != null) return@withTransaction
        // A cancelled job or an expired worker must never publish into a newer attempt.
        if (job.status != JobStatus.RUNNING || job.attempt != attempt) return@withTransaction
        val recording = events.get(job.recordingId)
        if (recording?.status == "DELETED") {
            commitJob(jobId, AiJobEvent.Cancelled, "$jobId:cancel-deleted", now, 0)
            dao.saveOutbox(OutboxRow(jobId, "DONE"))
            return@withTransaction
        }
        val text = text(job.recordingId)
        val contentId = UUID.randomUUID().toString()
        if (job.sourceContentId != null && text.transcriptContentId != job.sourceContentId) {
            saveBody(contentId, job.capability, result, now)
            commitJob(jobId, AiJobEvent.MarkedStale(contentId), "$jobId:stale:$attempt", now, 0)
            if (result is StoredResult.Title && text.titleOrigin == TextOrigin.USER) {
                commitText(job.recordingId, TextEvent.TitleSuggested(contentId), "${job.recordingId}:title:$contentId", now, result.text)
            }
            if (result is StoredResult.Summary && text.summaryOrigin == TextOrigin.USER) {
                commitText(job.recordingId, TextEvent.SummarySuggested(contentId), "${job.recordingId}:summary:$contentId", now, result.text)
            }
            dao.saveOutbox(OutboxRow(jobId, "DONE"))
            return@withTransaction
        }
        saveBody(contentId, job.capability, result, now)
        commitJob(jobId, AiJobEvent.Completed(contentId), "$jobId:complete:$attempt", now, 0)
        when (result) {
            is StoredResult.Transcript -> commitText(job.recordingId, TextEvent.TranscriptSet(contentId, TextOrigin.AI.name), "${job.recordingId}:transcript:$contentId", now, result.text)
            is StoredResult.Title -> commitText(job.recordingId, titleEventFor(text(job.recordingId), contentId), "${job.recordingId}:title:$contentId", now, result.text)
            is StoredResult.Summary -> commitText(job.recordingId, summaryEventFor(text(job.recordingId), contentId), "${job.recordingId}:summary:$contentId", now, result.text)
            is StoredResult.Memories -> proposeAll(job.recordingId, text.transcriptContentId.orEmpty(), result.items, now)
        }
        if (result is StoredResult.Transcript) {
            followUps.forEach { follow ->
                commitJob(follow.id, AiJobEvent.Requested(job.recordingId, follow.capability, follow.generation, contentId), "${follow.id}:request", now, 0)
                dao.saveOutbox(OutboxRow(follow.id, "PENDING"))
            }
        }
        dao.saveOutbox(OutboxRow(jobId, "DONE"))
    }

    suspend fun fail(jobId: String, attempt: Int, reason: String, terminal: Boolean, now: Long) = db.withTransaction {
        val job = dao.job(jobId)?.domain() ?: return@withTransaction
        if (job.status != JobStatus.RUNNING || job.attempt != attempt) return@withTransaction
        commitJob(jobId, AiJobEvent.AttemptFailed(attempt, reason.take(200), terminal), "$jobId:fail:$attempt", now, if (terminal) 0 else now + 30_000)
        dao.saveOutbox(OutboxRow(jobId, if (terminal) "DONE" else "PENDING"))
    }

    suspend fun cancel(jobId: String, now: Long) = db.withTransaction {
        val job = dao.job(jobId)?.domain() ?: return@withTransaction
        if (job.status != JobStatus.REQUESTED && job.status != JobStatus.RUNNING) {
            dao.saveOutbox(OutboxRow(jobId, "DONE"))
            return@withTransaction
        }
        commitJob(jobId, AiJobEvent.Cancelled, "$jobId:cancel:${job.version}", now, 0)
        dao.saveOutbox(OutboxRow(jobId, "DONE"))
    }

    suspend fun reviseTranscript(recordingId: String, value: String, now: Long) = db.withTransaction {
        val body = value.trim()
        require(body.isNotBlank()) { "转写不能为空" }
        val contentId = UUID.randomUUID().toString()
        dao.saveContent(ContentRow(contentId, "transcript", body, now))
        commitText(recordingId, TextEvent.TranscriptSet(contentId, TextOrigin.USER.name), "$recordingId:transcript:$contentId", now, body)
        dao.memoriesFor(recordingId).map(MemoryRow::domain).filter { it.visible && it.sourceContentId != contentId }.forEach { item ->
            commitMemory(item.id, MemoryEvent.Invalidated(memoryFingerprint(item.text)), "${item.id}:transcript-revised:$contentId", now, null, null)
            purgeHistoryContent(recordingId, item.id)
            ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(item.id), now = now)
        }
        MemoryPlanningRepository(db).invalidateSnapshots(now)
        cancelOpen(recordingId, setOf("ASR", "TITLE", "SUMMARY", "MEMORY"), now)
    }

    suspend fun reviseTitle(recordingId: String, value: String, now: Long) = savePlain(recordingId, "title", value, now) { contentId ->
        TextEvent.TitleSet(contentId, TextOrigin.USER.name)
    }

    suspend fun reviseSummary(recordingId: String, value: String, now: Long) = savePlain(recordingId, "summary", value, now) { contentId ->
        TextEvent.SummarySet(contentId, TextOrigin.USER.name)
    }

    suspend fun acceptTitle(recordingId: String, now: Long) = db.withTransaction {
        val current = text(recordingId)
        val contentId = requireNotNull(current.titleSuggestionContentId) { "没有可采用的标题" }
        require(!current.titleSuggestion.isNullOrBlank()) { "没有可采用的标题" }
        commitText(recordingId, TextEvent.TitleSet(contentId, TextOrigin.USER.name), "$recordingId:accept-title:$contentId", now, current.titleSuggestion)
    }

    suspend fun acceptSummary(recordingId: String, now: Long) = db.withTransaction {
        val current = text(recordingId)
        val contentId = requireNotNull(current.summarySuggestionContentId) { "没有可采用的总结" }
        require(!current.summarySuggestion.isNullOrBlank()) { "没有可采用的总结" }
        commitText(recordingId, TextEvent.SummarySet(contentId, TextOrigin.USER.name), "$recordingId:accept-summary:$contentId", now, current.summarySuggestion)
    }

    suspend fun confirmMemory(id: String, now: Long) = db.withTransaction {
        commitMemory(id, MemoryEvent.Confirmed, "$id:confirm", now, null, null)
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    suspend fun correctMemory(id: String, value: String, now: Long) = db.withTransaction {
        val body = value.trim()
        require(body.isNotBlank()) { "记忆不能为空" }
        val contentId = UUID.randomUUID().toString()
        dao.saveContent(ContentRow(contentId, "memory", memoryBody(body, memory(id)?.evidence.orEmpty()), now))
        commitMemory(id, MemoryEvent.Corrected(contentId), "$id:correct:$contentId", now, body, memory(id)?.evidence)
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    suspend fun forgetMemory(id: String, now: Long) = terminalMemory(id, now, "forget") { MemoryEvent.Forgotten(it) }

    suspend fun disableMemory(id: String, now: Long) = terminalMemory(id, now, "disable") { MemoryEvent.Invalidated(it) }

    suspend fun mergeMemory(sourceId: String, targetId: String, now: Long) = db.withTransaction {
        val source = requireNotNull(memory(sourceId)) { "找不到要合并的记忆" }
        val target = requireNotNull(memory(targetId)) { "找不到合并目标" }
        require(source.visible && target.visible) { "只能合并仍有效的记忆" }
        require(source.sourceRecordingId == target.sourceRecordingId && source.sourceConversationId == target.sourceConversationId) { "请合并同一录音或对话中的记忆" }
        val combined = listOf(target.text, source.text).filter { it.isNotBlank() }.distinct().joinToString("\n")
        val contentId = UUID.randomUUID().toString()
        dao.saveContent(ContentRow(contentId, "memory", memoryBody(combined, target.evidence), now))
        commitMemory(targetId, MemoryEvent.Corrected(contentId), "$targetId:merge-from:$sourceId", now, combined, target.evidence)
        commitMemory(sourceId, MemoryEvent.Merged(targetId, memoryFingerprint(source.text)), "$sourceId:merged:$targetId", now, null, null)
        purgeHistoryContent(source.sourceRecordingId, memoryId = sourceId)
        ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(sourceId), now = now)
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    suspend fun onRecordingDeleted(recordingId: String, now: Long) = db.withTransaction {
        MemoryPlanningRepository(db).invalidateSnapshots(now)
        cancelOpen(recordingId, setOf("ASR", "TITLE", "SUMMARY", "MEMORY"), now)
        dao.memoriesFor(recordingId).map(MemoryRow::domain).filter { it.visible }.forEach { item ->
            commitMemory(item.id, MemoryEvent.Invalidated(memoryFingerprint(item.text)), "${item.id}:source-deleted", now, null, null)
            dao.deleteContent(item.contentId)
            ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(item.id), now = now)
        }
        val current = dao.text(recordingId)?.domain()
        if (current != null) {
            commitText(recordingId, TextEvent.Cleared, "$recordingId:text-cleared", now, null)
            listOfNotNull(current.transcriptContentId, current.titleContentId, current.summaryContentId, current.titleSuggestionContentId, current.summarySuggestionContentId)
                .forEach { dao.deleteContent(it) }
        }
        purgeHistoryContent(recordingId)
    }

    /** Rebuild projections from events and saved content. Never creates outbox work. */
    suspend fun replay() = db.withTransaction {
        val texts = linkedMapOf<String, RecordingText>()
        val jobs = linkedMapOf<String, AiJob>()
        val memories = linkedMapOf<String, MemoryItem>()
        events.events().forEach { row ->
            require(row.schemaVersion == 1) { "Unsupported event schema" }
            when (row.aggregateType) {
                "Recording", "Conversation", "AssistantTurn", "MemoryPlanning" -> Unit
                "RecordingText" -> {
                    val recordingId = row.aggregateId.removePrefix("text:")
                    val state = texts[recordingId] ?: RecordingText(recordingId)
                    check(row.aggregateVersion == state.version + 1) { "Non-contiguous aggregate history" }
                    val event = eventJson.decodeFromString(TextEvent.serializer(), row.payload)
                    texts[recordingId] = evolveText(state, event, contentBody(event))
                }
                "AiJob" -> {
                    val state = jobs[row.aggregateId] ?: AiJob(row.aggregateId)
                    check(row.aggregateVersion == state.version + 1) { "Non-contiguous aggregate history" }
                    jobs[row.aggregateId] = evolveJob(state, eventJson.decodeFromString(AiJobEvent.serializer(), row.payload))
                }
                "Memory" -> {
                    val state = memories[row.aggregateId] ?: MemoryItem(row.aggregateId)
                    check(row.aggregateVersion == state.version + 1) { "Non-contiguous aggregate history" }
                    val event = eventJson.decodeFromString(MemoryEvent.serializer(), row.payload)
                    val body = memoryContent(event)
                    memories[row.aggregateId] = evolveMemory(state, event, body?.text, body?.evidence)
                }
                else -> error("Unsupported event schema")
            }
        }
        dao.clearTexts()
        dao.clearJobs()
        dao.clearMemories()
        texts.values.forEach { dao.saveText(RecordingTextRow.from(it)) }
        jobs.values.forEach { dao.saveJob(AiJobRow.from(it, 0)) }
        memories.values.forEach { dao.saveMemory(MemoryRow.from(it)) }
    }

    private suspend fun proposeAll(recordingId: String, sourceContentId: String, items: List<MemoryDraft>, now: Long) {
        val existing = dao.memories().map(MemoryRow::domain)
        items.distinctBy { memoryFingerprint(it.text) }.forEach { item ->
            if (duplicatesMemory(existing, recordingId, item.text)) return@forEach
            val id = UUID.randomUUID().toString()
            val contentId = UUID.randomUUID().toString()
            dao.saveContent(ContentRow(contentId, "memory", memoryBody(item.text, item.evidence), now))
            commitMemory(
                id,
                MemoryEvent.Proposed(item.type.name, contentId, recordingId, sourceContentId),
                "$recordingId:memory:$contentId",
                now,
                item.text,
                item.evidence
            )
        }
    }

    private suspend fun savePlain(recordingId: String, kind: String, value: String, now: Long, event: (String) -> TextEvent) = db.withTransaction {
        val body = value.trim()
        require(body.isNotBlank()) { "内容不能为空" }
        val contentId = UUID.randomUUID().toString()
        dao.saveContent(ContentRow(contentId, kind, body, now))
        commitText(recordingId, event(contentId), "$recordingId:$kind:$contentId", now, body)
    }

    private suspend fun terminalMemory(id: String, now: Long, name: String, event: (String) -> MemoryEvent) = db.withTransaction {
        val current = requireNotNull(memory(id)) { "找不到记忆" }
        if (!current.visible) return@withTransaction
        commitMemory(id, event(memoryFingerprint(current.text)), "$id:$name", now, null, null)
        purgeHistoryContent(current.sourceRecordingId, memoryId = id)
        ConversationRepository(db).withdrawDerivedContent(memoryIds = setOf(id), now = now)
        MemoryPlanningRepository(db).invalidateSnapshots(now)
    }

    /** Tombstones retain metadata; all associated historic bodies must also be removed. */
    private suspend fun purgeHistoryContent(recordingId: String, memoryId: String? = null) {
        events.events().filter { row ->
            if (memoryId != null) row.aggregateType == "Memory" && row.aggregateId == memoryId else row.correlationId == recordingId
        }.forEach { row ->
            val payload = eventJson.parseToJsonElement(row.payload).jsonObject
            listOf("contentId", "resultContentId").forEach { field ->
                payload[field]?.jsonPrimitive?.content?.let { dao.deleteContent(it) }
            }
        }
        if (memoryId != null) {
            // Older versions also stored a redundant joined copy of memory results in job bodies.
            dao.jobsFor(recordingId).filter { it.capability == "MEMORY" }.forEach { row ->
                row.resultContentId?.let { dao.deleteContent(it) }
            }
        }
    }

    private suspend fun cancelOpen(recordingId: String, capabilities: Set<String>, now: Long) {
        dao.jobsFor(recordingId).map(AiJobRow::domain).filter { it.capability in capabilities && it.status in setOf(JobStatus.REQUESTED, JobStatus.RUNNING) }.forEach { job ->
            commitJob(job.id, AiJobEvent.Cancelled, "${job.id}:cancel:${job.version}", now, 0)
            dao.saveOutbox(OutboxRow(job.id, "DONE"))
        }
    }

    private suspend fun saveBody(id: String, capability: String, result: StoredResult, now: Long) {
        val body = when (result) {
            is StoredResult.Transcript -> result.text
            is StoredResult.Title -> result.text
            is StoredResult.Summary -> result.text
            is StoredResult.Memories -> "${result.items.size} candidates"
        }
        dao.saveContent(ContentRow(id, capability, body, now))
    }

    private suspend fun commitJob(id: String, event: AiJobEvent, commandId: String, now: Long, leaseUntil: Long): AiJob {
        val payload = eventJson.encodeToString(AiJobEvent.serializer(), event)
        events.command(commandId)?.let { prior ->
            require(prior.aggregateId == id && prior.payload == payload) { "Idempotency key reused for a different command" }
            return requireNotNull(dao.job(id)).domain()
        }
        val version = events.version(id) ?: 0
        val current = dao.job(id)?.domain() ?: AiJob(id)
        check(current.version == version) { "Projection needs rebuilding" }
        validateJob(current, event)
        val next = evolveJob(current, event)
        insertEvent("AiJob", id, next.version, payload, commandId, now, next.recordingId.ifBlank { id })
        dao.saveJob(AiJobRow.from(next, leaseUntil))
        return next
    }

    private suspend fun commitText(recordingId: String, event: TextEvent, commandId: String, now: Long, body: String?): RecordingText {
        val aggregateId = "text:$recordingId"
        val payload = eventJson.encodeToString(TextEvent.serializer(), event)
        events.command(commandId)?.let { prior ->
            require(prior.aggregateId == aggregateId && prior.payload == payload) { "Idempotency key reused for a different command" }
            return text(recordingId)
        }
        val version = events.version(aggregateId) ?: 0
        val current = dao.text(recordingId)?.domain() ?: RecordingText(recordingId)
        check(current.version == version) { "Projection needs rebuilding" }
        validateText(current, event)
        val next = evolveText(current, event, body)
        insertEvent("RecordingText", aggregateId, next.version, payload, commandId, now, recordingId)
        dao.saveText(RecordingTextRow.from(next))
        return next
    }

    private suspend fun commitMemory(id: String, event: MemoryEvent, commandId: String, now: Long, text: String?, evidence: String?): MemoryItem {
        val payload = eventJson.encodeToString(MemoryEvent.serializer(), event)
        events.command(commandId)?.let { prior ->
            require(prior.aggregateId == id && prior.payload == payload) { "Idempotency key reused for a different command" }
            return requireNotNull(dao.memory(id)).domain()
        }
        val version = events.version(id) ?: 0
        val current = dao.memory(id)?.domain() ?: MemoryItem(id)
        check(current.version == version) { "Projection needs rebuilding" }
        validateMemory(current, event)
        val next = evolveMemory(current, event, text, evidence)
        insertEvent("Memory", id, next.version, payload, commandId, now, next.sourceRecordingId.ifBlank { next.sourceConversationId.ifBlank { id } })
        dao.saveMemory(MemoryRow.from(next))
        return next
    }

    private suspend fun insertEvent(aggregateType: String, aggregateId: String, version: Int, payload: String, commandId: String, now: Long, correlationId: String) {
        val position = events.insert(
            EventRow(
                eventId = UUID.randomUUID().toString(),
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                aggregateVersion = version,
                eventType = eventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content,
                payload = payload,
                occurredAt = now,
                recordedAt = now,
                correlationId = correlationId,
                causationId = commandId,
                commandId = commandId
            )
        )
        events.checkpoint(ProjectionCheckpoint(position = position))
    }

    private suspend fun contentBody(event: TextEvent): String? {
        val id = when (event) {
            is TextEvent.TranscriptSet -> event.contentId
            is TextEvent.TitleSet -> event.contentId
            is TextEvent.TitleSuggested -> event.contentId
            is TextEvent.SummarySet -> event.contentId
            is TextEvent.SummarySuggested -> event.contentId
            TextEvent.MarkedStale, TextEvent.Cleared -> return null
        }
        return dao.content(id)?.body
    }

    private suspend fun memoryContent(event: MemoryEvent): MemoryBody? {
        val id = when (event) {
            is MemoryEvent.Proposed -> event.contentId
            is MemoryEvent.FromConversation -> event.contentId
            is MemoryEvent.Corrected -> event.contentId
            else -> return null
        }
        val row = dao.content(id) ?: return null
        return runCatching { eventJson.decodeFromString<MemoryBody>(row.body) }.getOrElse { MemoryBody(row.body, "") }
    }
}

@Serializable
private data class MemoryBody(val text: String, val evidence: String = "")

private fun memoryBody(text: String, evidence: String) = eventJson.encodeToString(MemoryBody(text, evidence))
