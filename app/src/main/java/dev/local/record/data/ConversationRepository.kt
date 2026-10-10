package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.Conversation
import dev.local.record.domain.ConversationEvent
import dev.local.record.domain.TurnEvent
import dev.local.record.domain.TurnStatus
import dev.local.record.domain.evolveConversation
import dev.local.record.domain.evolveTurn
import dev.local.record.domain.validateTurn
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local conversations and AI attempts are facts; replay has no network or scheduling dependency. */
class ConversationRepository(private val db: RecordDatabase) {
    private val dao = db.conversations()
    private val events = db.recordings()
    private val content = db.processing()
    val conversations = dao.observeConversations().map { it.map(ConversationRow::domain) }
    val turns = dao.observeTurns().map { it.map(AssistantTurnRow::domain) }

    suspend fun turn(id: String) = dao.turn(id)?.domain()
    suspend fun turns(id: String) = dao.turns(id).map(AssistantTurnRow::domain)
    suspend fun context(id: String): AssistantContext? = content.content(id)?.body?.let { eventJson.decodeFromString<AssistantContext>(it) }

    suspend fun create(id: String, now: Long) = db.withTransaction {
        val existing = dao.conversation(id)?.domain()
        if (existing != null) {
            require(!existing.deleted) { "对话已删除" }
            existing
        } else {
            commitConversation(id, ConversationEvent.Created(now), now)
        }
    }

    suspend fun request(id: String, conversationId: String, text: String, now: Long) = db.withTransaction {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 4_000) { "每条消息最多 4000 字" }
        val existing = turn(id)
        if (existing != null) {
            require(existing.conversationId == conversationId && existing.userText == body) { "消息 ID 已被使用" }
            existing
        } else {
            require(dao.conversation(conversationId)?.deleted == false) { "对话不存在或已删除" }
            val previous = turns(conversationId)
            require(previous.none { it.status in setOf(TurnStatus.RUNNING, TurnStatus.REQUESTED) }) { "请等待当前回复或先停止" }
            val contentId = "$id:user"
            content.saveContent(ContentRow(contentId, "chat-user", body, now))
            commitTurn(id, TurnEvent.Requested(conversationId, (previous.maxOfOrNull { it.sequence } ?: 0) + 1, contentId), now, body)
        }
    }

    suspend fun start(id: String, snapshot: AssistantContext, now: Long): AssistantTurn = db.withTransaction {
        val state = requireNotNull(turn(id))
        require(dao.conversation(state.conversationId)?.deleted == false) { "对话已删除" }
        require(turns(state.conversationId).none { it.id != id && it.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) }) { "已有回复在进行中" }
        val contentId = "$id:context:${state.attempt + 1}"
        content.saveContent(ContentRow(contentId, "chat-context", eventJson.encodeToString(snapshot), now))
        commitTurn(id, TurnEvent.Started(state.attempt + 1, contentId), now)
    }

    suspend fun answer(id: String, attempt: Int, reply: String, memories: List<MemoryDraft>, now: Long): Boolean = db.withTransaction {
        val state = turn(id) ?: return@withTransaction false
        if (state.status != TurnStatus.RUNNING || state.attempt != attempt || dao.conversation(state.conversationId)?.deleted != false) return@withTransaction false
        val snapshot = state.contextContentId?.let { context(it) } ?: error("缺少请求上下文")
        val valid = snapshot.memories.all { reference ->
            val memory = content.memory(reference.id)?.domain()
            memory?.version == reference.version && memory.status == dev.local.record.domain.MemoryStatus.CONFIRMED
        } && snapshot.historyMemories.all { reference ->
            val memory = content.memory(reference.id)?.domain()
            memory?.version == reference.version && memory.visible
        } && snapshot.historyTurnIds.all { turn(it)?.status == TurnStatus.ANSWERED }
        if (!valid) {
            commitTurn(id, TurnEvent.Failed(attempt, "CONTEXT_CHANGED"), now)
            return@withTransaction false
        }
        require(reply.isNotBlank() && reply.length <= 20_000) { "回复为空或过长" }
        val contentId = "$id:reply:$attempt"
        content.saveContent(ContentRow(contentId, "chat-reply", reply, now))
        commitTurn(id, TurnEvent.Answered(attempt, contentId), now, reply)
        ProcessingRepository(db).proposeConversationMemories(id, memories, now)
        true
    }

    suspend fun fail(id: String, attempt: Int, reason: String, now: Long) = db.withTransaction {
        val state = turn(id) ?: return@withTransaction
        if (state.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) && state.attempt == attempt) commitTurn(id, TurnEvent.Failed(attempt, reason), now)
    }

    suspend fun cancel(id: String, now: Long, expectedAttempt: Int? = null) = db.withTransaction {
        val state = turn(id) ?: return@withTransaction
        if (expectedAttempt != null && expectedAttempt != state.attempt) return@withTransaction
        if (state.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING)) commitTurn(id, TurnEvent.Cancelled, now)
    }

    /** Process restart exposes uncertain requests for explicit retry, without automatic uploads. */
    suspend fun recoverInterrupted(now: Long) = db.withTransaction {
        dao.interrupted().forEach { fail(it.id, it.attempt, "INTERRUPTED", now) }
    }

    suspend fun delete(id: String, now: Long) = db.withTransaction {
        val conversation = dao.conversation(id)?.domain() ?: return@withTransaction
        if (conversation.deleted) return@withTransaction
        commitConversation(id, ConversationEvent.Deleted, now)
        turns(id).filter { it.status != TurnStatus.DELETED }.forEach { commitTurn(it.id, TurnEvent.Deleted, now) }
        ProcessingRepository(db).onConversationDeleted(id, now)
        // Historic failed attempts and custom prompts are also deletable content.
        events.events().filter { it.aggregateType == "AssistantTurn" && it.correlationId == id }.forEach { row ->
            val payload = eventJson.parseToJsonElement(row.payload).jsonObject
            listOf("userContentId", "contextContentId", "replyContentId").forEach { key -> payload[key]?.jsonPrimitive?.content?.let { content.deleteContent(it) } }
        }
    }

    suspend fun replay() = db.withTransaction {
        val conversations = linkedMapOf<String, Conversation>()
        val turns = linkedMapOf<String, AssistantTurn>()
        events.events().filter { it.aggregateType in setOf("Conversation", "AssistantTurn") }.forEach { row ->
            require(row.schemaVersion == 1) { "Unsupported conversation event schema" }
            if (row.aggregateType == "Conversation") {
                val state = conversations[row.aggregateId] ?: Conversation(row.aggregateId)
                check(row.aggregateVersion == state.version + 1)
                conversations[row.aggregateId] = evolveConversation(state, eventJson.decodeFromString(ConversationEvent.serializer(), row.payload))
            } else {
                val state = turns[row.aggregateId] ?: AssistantTurn(row.aggregateId)
                check(row.aggregateVersion == state.version + 1)
                val event = eventJson.decodeFromString(TurnEvent.serializer(), row.payload)
                val contentId = when (event) {
                    is TurnEvent.Requested -> event.userContentId
                    is TurnEvent.Answered -> event.replyContentId
                    else -> null
                }
                turns[row.aggregateId] = evolveTurn(state, event, contentId?.let { content.content(it)?.body })
            }
        }
        dao.clearTurns()
        dao.clearConversations()
        conversations.values.forEach { dao.saveConversation(ConversationRow.from(it)) }
        turns.values.forEach { dao.saveTurn(AssistantTurnRow.from(it)) }
    }

    private suspend fun commitConversation(id: String, event: ConversationEvent, now: Long): Conversation {
        val current = dao.conversation(id)?.domain() ?: Conversation(id)
        check(current.version == (events.version(id) ?: 0))
        require(if (event is ConversationEvent.Created) current.version == 0 else current.version > 0 && !current.deleted)
        val next = evolveConversation(current, event)
        insert("Conversation", id, next.version, eventJson.encodeToString(ConversationEvent.serializer(), event), id, now)
        dao.saveConversation(ConversationRow.from(next))
        return next
    }

    private suspend fun commitTurn(id: String, event: TurnEvent, now: Long, body: String? = null): AssistantTurn {
        val current = turn(id) ?: AssistantTurn(id)
        check(current.version == (events.version(id) ?: 0))
        validateTurn(current, event)
        val next = evolveTurn(current, event, body)
        insert("AssistantTurn", id, next.version, eventJson.encodeToString(TurnEvent.serializer(), event), next.conversationId, now)
        dao.saveTurn(AssistantTurnRow.from(next))
        return next
    }

    private suspend fun insert(type: String, id: String, version: Int, payload: String, correlationId: String, now: Long) {
        val commandId = "$id:$version"
        val position = events.insert(
            EventRow(
                eventId = UUID.randomUUID().toString(), aggregateType = type, aggregateId = id, aggregateVersion = version,
                eventType = eventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content, payload = payload, occurredAt = now, recordedAt = now,
                correlationId = correlationId, causationId = commandId, commandId = commandId
            )
        )
        events.checkpoint(ProjectionCheckpoint(position = position))
    }
}
