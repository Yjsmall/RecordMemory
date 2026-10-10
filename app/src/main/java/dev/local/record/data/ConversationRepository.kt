package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.Conversation
import dev.local.record.domain.ConversationEvent
import dev.local.record.domain.TurnEvent
import dev.local.record.domain.TurnStatus
import dev.local.record.domain.currentAt
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
    suspend fun memoryRevision(): Long = events.memoryRevision()

    /** Reads current unmasked source versions; corrections must belong to a current confirmed memory. */
    suspend fun sourceText(id: String): String? = db.withTransaction {
        HistorySearchRepository(db).read(id)?.text ?: content.memories().map(MemoryRow::domain).firstOrNull { memory ->
            memory.currentAt(System.currentTimeMillis()) && memory.fact?.sources?.any { it.contentId == id && it.origin == "USER_CORRECTION" } == true
        }?.let { content.content(id)?.body?.let { eventJson.decodeFromString<MemoryBody>(it).text } }
    }

    suspend fun searchSources(query: dev.local.record.domain.HistoryQuery) = HistorySearchRepository(db).search(query)
    suspend fun readSource(id: String) = HistorySearchRepository(db).read(id)

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
            commitTurn(id, TurnEvent.Requested(conversationId, (previous.maxOfOrNull { it.sequence } ?: 0) + 1, contentId, java.time.ZoneId.systemDefault().id), now, body)
        }
    }

    suspend fun start(id: String, snapshot: AssistantContext, now: Long, expectedVersion: Int? = null): AssistantTurn = db.withTransaction {
        val state = requireNotNull(turn(id))
        require(expectedVersion == null || state.version == expectedVersion) { "请求已变化" }
        require(dao.conversation(state.conversationId)?.deleted == false) { "对话已删除" }
        require(turns(state.conversationId).none { it.id != id && it.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) }) { "已有回复在进行中" }
        require(snapshotIsValid(snapshot)) { "请求上下文已变化" }
        val contentId = "$id:context:${state.attempt + 1}"
        content.saveContent(ContentRow(contentId, "chat-context", eventJson.encodeToString(snapshot), now))
        commitTurn(id, TurnEvent.Started(state.attempt + 1, contentId), now)
    }

    suspend fun isCurrent(id: String, attempt: Int): Boolean = db.withTransaction {
        val state = turn(id) ?: return@withTransaction false
        val snapshot = state.contextContentId?.let { context(it) } ?: return@withTransaction false
        state.status == TurnStatus.RUNNING && state.attempt == attempt && dao.conversation(state.conversationId)?.deleted == false && snapshotIsValid(snapshot)
    }

    private suspend fun snapshotIsValid(snapshot: AssistantContext): Boolean = snapshot.memories.all { reference ->
        val memory = content.memory(reference.id)?.domain()
        memory?.version == reference.version && memory.currentAt(System.currentTimeMillis())
    } && snapshot.historyMemories.all { reference ->
        val memory = content.memory(reference.id)?.domain()
        memory?.version == reference.version && memory.visible && (memory.fact?.effectiveAt(System.currentTimeMillis()) != false)
    } && snapshot.historyTurnIds.all { turn(it)?.status == TurnStatus.ANSWERED } &&
        (snapshot.memoryRevision == null || snapshot.memoryRevision == events.memoryRevision()) &&
        snapshot.sourceContentIds.all { sourceText(it) != null }

    /** Tool receipt and newly used dependencies are committed together before returning to the model. */
    suspend fun completeTool(id: String, attempt: Int, callId: String, receipt: String, memories: List<dev.local.record.domain.MemoryReference>, sourceContentIds: List<String>, now: Long): Boolean = db.withTransaction {
        require(callId.isNotBlank() && callId.length <= 200 && callId.none(Char::isISOControl) && receipt.length <= 16_000)
        val state = turn(id) ?: return@withTransaction false
        if (state.status != TurnStatus.RUNNING || state.attempt != attempt) return@withTransaction false
        val snapshot = state.contextContentId?.let { context(it) } ?: return@withTransaction false
        val receiptId = "$id:tool:$attempt:$callId"
        content.content(receiptId)?.let { previous ->
            require(previous.body == receipt) { "工具回执参数已变化" }
            return@withTransaction snapshotIsValid(snapshot)
        }
        require(snapshot.toolReceiptIds.size < 6) { "本轮工具调用超过限额" }
        val next = snapshot.copy(memories = (snapshot.memories + memories).distinct(), sourceContentIds = (snapshot.sourceContentIds + sourceContentIds).distinct(), toolReceiptIds = snapshot.toolReceiptIds + receiptId)
        if (!snapshotIsValid(next)) return@withTransaction false
        val contextId = "$id:context:$attempt:tool:${state.version + 1}"
        content.saveContent(ContentRow(receiptId, "agent-tool-receipt", receipt, now))
        content.saveContent(ContentRow(contextId, "chat-context", eventJson.encodeToString(next), now))
        commitTurn(id, TurnEvent.ToolCompleted(attempt, callId, contextId, receiptId), now)
        true
    }

    suspend fun answer(id: String, attempt: Int, reply: String, memories: List<MemoryDraft>, now: Long): Boolean = db.withTransaction {
        val state = turn(id) ?: return@withTransaction false
        if (state.status != TurnStatus.RUNNING || state.attempt != attempt || dao.conversation(state.conversationId)?.deleted != false) return@withTransaction false
        val snapshot = state.contextContentId?.let { context(it) } ?: error("缺少请求上下文")
        val valid = snapshotIsValid(snapshot)
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
        withdrawDerivedContent(deletedTurnIds = turns(id).map { it.id }.toSet(), now = now)
        ProcessingRepository(db).onConversationDeleted(id, now)
        // Historic failed attempts and custom prompts are also deletable content.
        events.events().filter { it.aggregateType == "AssistantTurn" && it.correlationId == id }.forEach { row ->
            val payload = eventJson.parseToJsonElement(row.payload).jsonObject
            listOf("userContentId", "contextContentId", "replyContentId", "receiptContentId").forEach { key -> payload[key]?.jsonPrimitive?.content?.let { content.deleteContent(it) } }
        }
    }

    /** Removes every historic derived reply/context, retaining user messages under their own scope. */
    suspend fun withdrawDerivedContent(memoryIds: Set<String> = emptySet(), deletedTurnIds: Set<String> = emptySet(), withdrawnSourceContentIds: Set<String> = emptySet(), now: Long) = db.withTransaction {
        val affected = deletedTurnIds.toMutableSet()
        affected.addAll(content.memories().filter { it.id in memoryIds }.flatMap { listOf(it.sourceTurnId) + it.domain().fact?.sources.orEmpty().map { source -> source.turnId } }.filter { it.isNotBlank() })
        val history = events.events().filter { it.aggregateType == "AssistantTurn" }
        val snapshots = history.mapNotNull { row ->
            val id = eventJson.parseToJsonElement(row.payload).jsonObject["contextContentId"]?.jsonPrimitive?.content
            id?.let { context(it) }?.let { row.aggregateId to it }
        }
        val withdrawnBodies = withdrawnSourceContentIds.toMutableSet()
        val replyIds = history.groupBy { it.aggregateId }.mapValues { (_, rows) ->
            rows.mapNotNull { eventJson.parseToJsonElement(it.payload).jsonObject["replyContentId"]?.jsonPrimitive?.content }
        }
        history.filter { it.aggregateId in deletedTurnIds }.forEach { row ->
            eventJson.parseToJsonElement(row.payload).jsonObject["userContentId"]?.jsonPrimitive?.content?.let(withdrawnBodies::add)
        }
        do {
            val before = affected.size
            affected.forEach { withdrawnBodies.addAll(replyIds[it].orEmpty()) }
            snapshots.forEach { (turnId, snapshot) ->
                if ((snapshot.memories + snapshot.historyMemories).any { it.id in memoryIds } || snapshot.historyTurnIds.any { it in affected } || snapshot.sourceContentIds.any { it in withdrawnBodies }) affected.add(turnId)
            }
        } while (affected.size != before)
        affected.forEach { id ->
            val state = turn(id)
            if (state != null && state.status != TurnStatus.DELETED) commitTurn(id, TurnEvent.ContextWithdrawn, now)
        }
        history.filter { it.aggregateId in affected }.forEach { row ->
            val payload = eventJson.parseToJsonElement(row.payload).jsonObject
            listOf("contextContentId", "replyContentId", "receiptContentId").forEach { key -> payload[key]?.jsonPrimitive?.content?.let { content.deleteContent(it) } }
        }
    }

    /** Upgrade recovery also removes copies left by older versions; it never calls a provider. */
    suspend fun purgeWithdrawnMemoryContent(now: Long) = db.withTransaction {
        val memories = content.memories().filter { it.status in setOf("FORGOTTEN", "INVALIDATED", "MERGED") }
        val history = events.events()
        val pending = memories.filter { memory ->
            val revokedAt = history.lastOrNull { it.aggregateType == "Memory" && it.aggregateId == memory.id && it.eventType in setOf("MemoryForgotten", "MemoryInvalidated", "MemoryMerged") }?.globalPosition ?: 0
            val cleanedAt = history.lastOrNull { it.aggregateType == "AssistantTurn" && it.aggregateId == memory.sourceTurnId && it.eventType == "AssistantContextWithdrawn" }?.globalPosition ?: 0
            memory.sourceTurnId.isBlank() || cleanedAt <= revokedAt
        }
        if (pending.isNotEmpty()) withdrawDerivedContent(memoryIds = pending.map { it.id }.toSet(), now = now)
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
