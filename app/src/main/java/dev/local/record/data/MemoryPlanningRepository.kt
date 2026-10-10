package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.MemoryPlanningEvent
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryPlanningTask
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
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
    val binding: CapabilityBinding
)

/** Event-first planning commands. This repository cannot call a provider or schedule work. */
class MemoryPlanningRepository(private val db: RecordDatabase) {
    private val dao = db.memoryPlanning()
    val tasks = dao.observe().map { rows -> rows.map(MemoryPlanningRow::domain) }

    suspend fun task(id: String) = dao.get(id)?.domain()
    suspend fun input(task: MemoryPlanningTask): MemoryPlanningInput? = db.processing().content(task.requestContentId)?.body?.let { eventJson.decodeFromString(it) }

    suspend fun request(turnId: String, connection: AiConnection, binding: CapabilityBinding, memories: List<MemoryReference>, now: Long): MemoryPlanningTask = db.withTransaction {
        require(binding.capability == AiCapability.MEMORY && binding.connectionId == connection.id && binding.model.isNotBlank() && connection.supports(AiCapability.MEMORY)) { "记忆模型配置不兼容" }
        require(memories.size <= 10 && memories.distinctBy { it.id }.size == memories.size) { "记忆引用超限或重复" }
        val existing = dao.all().map(MemoryPlanningRow::domain).lastOrNull { it.turnId == turnId && it.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING, MemoryPlanningStatus.COMPLETED) }
        if (existing != null) return@withTransaction existing
        val turn = requireNotNull(db.conversations().turn(turnId)).domain()
        require(turn.status == TurnStatus.ANSWERED && db.conversations().conversation(turn.conversationId)?.deleted == false) { "请先完成对话回复" }
        require(db.processing().memories().none { it.sourceTurnId == turnId && it.status in setOf("FORGOTTEN", "INVALIDATED", "MERGED") }) { "此消息已有忘记或失效的记忆；如需重新记住，请手动保存" }
        val id = "memory-plan:${UUID.randomUUID()}"
        val contentId = "$id:input"
        val input = MemoryPlanningInput(turn.userContentId, db.recordings().memoryRevision(), memories, connection, binding)
        db.processing().saveContent(ContentRow(contentId, "memory-planning-input", eventJson.encodeToString(input), now))
        commit(MemoryPlanningTask(id), MemoryPlanningEvent.Requested(turnId, contentId, now), now)
    }

    /** Recheck immediately before uploading. A changed knowledge base invalidates the whole batch. */
    suspend fun start(id: String, now: Long): MemoryPlanningInput? = db.withTransaction {
        val current = task(id) ?: return@withTransaction null
        if (current.status != MemoryPlanningStatus.REQUESTED) return@withTransaction null
        val input = input(current)
        if (input == null || !valid(current, input)) {
            commit(current, MemoryPlanningEvent.Failed("SOURCE_CHANGED"), now)
            return@withTransaction null
        }
        commit(current, MemoryPlanningEvent.Started, now)
        input
    }

    suspend fun isCurrent(id: String): Boolean = db.withTransaction {
        val current = task(id) ?: return@withTransaction false
        val input = input(current) ?: return@withTransaction false
        current.status == MemoryPlanningStatus.RUNNING && valid(current, input)
    }

    suspend fun complete(id: String, items: List<MemoryDraft>, now: Long): Boolean = db.withTransaction {
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
        val before = processing.memories().count { it.sourceTurnId == current.turnId }
        processing.proposeConversationMemories(current.turnId, items, now)
        val count = processing.memories().count { it.sourceTurnId == current.turnId } - before
        commit(current, MemoryPlanningEvent.Completed(count), now)
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

    suspend fun recoverInterrupted(now: Long) {
        dao.all().filter { it.status in setOf("REQUESTED", "RUNNING") }.forEach { fail(it.id, "INTERRUPTED", now) }
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

    private suspend fun valid(task: MemoryPlanningTask, input: MemoryPlanningInput): Boolean {
        val turn = db.conversations().turn(task.turnId)?.domain() ?: return false
        return turn.status == TurnStatus.ANSWERED && turn.userContentId == input.sourceContentId &&
            db.conversations().conversation(turn.conversationId)?.deleted == false &&
            db.processing().content(input.sourceContentId)?.body == turn.userText &&
            db.recordings().memoryRevision() == input.memoryRevision && input.memories.all { ref ->
                val memory = db.processing().memory(ref.id)?.domain()
                memory?.version == ref.version && memory.status == MemoryStatus.CONFIRMED
            }
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
