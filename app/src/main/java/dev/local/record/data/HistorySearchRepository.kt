package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.MemoryEvent
import dev.local.record.domain.MemoryPredicate
import dev.local.record.domain.TurnEvent
import dev.local.record.domain.selectHistoryHits

/** Reads original retained bodies; no duplicate index, embeddings, writes or external side effects. */
class HistorySearchRepository(private val db: RecordDatabase) {
    suspend fun search(query: HistoryQuery): List<HistoryHit> = db.withTransaction { selectHistoryHits(sources(query.project), query) }

    /** Returns the full body only while this exact version remains current and unmasked. */
    suspend fun read(contentId: String): HistoryHit? = db.withTransaction { sources().firstOrNull { it.contentId == contentId } }

    private suspend fun sources(project: String = ""): List<HistoryHit> {
        val events = db.recordings().events()
        val memories = db.processing().memories().map(MemoryRow::domain)
        val withdrawn = events.filter { it.aggregateType == "Memory" && it.eventType in setOf("MemoryForgotten", "MemoryInvalidated") }
        val withdrawnMemoryIds = withdrawn.map { it.aggregateId }.toSet()
        val masks = withdrawn.flatMap { row ->
            when (val event = eventJson.decodeFromString<MemoryEvent>(row.payload)) {
                is MemoryEvent.Forgotten -> event.sourceContentIds
                is MemoryEvent.Invalidated -> event.sourceContentIds
                else -> emptyList()
            }
        }.toMutableSet()
        // Old versions retained origin IDs in proposals even after erasing the fact body.
        events.filter { it.aggregateType == "Memory" && it.aggregateId in withdrawnMemoryIds && it.eventType in setOf("MemoryProposed", "ConversationMemoryProposed") }.forEach { row ->
            when (val event = eventJson.decodeFromString<MemoryEvent>(row.payload)) {
                is MemoryEvent.Proposed -> masks += event.sourceContentId
                is MemoryEvent.FromConversation -> masks += event.sourceContentId
                else -> Unit
            }
        }
        masks += memories.filter { it.id in withdrawnMemoryIds }.map { it.sourceContentId }
        val scopes = memories.filter { it.visible }.flatMap { memory -> memory.fact?.let { fact -> fact.sources.map { it.contentId to fact.scope } }.orEmpty() }.groupBy({ it.first }, { it.second })
        val suppressions = memories.filter { it.suppressionKey.isNotBlank() }.map { it.suppressionLabel }
        val result = mutableListOf<HistoryHit>()
        suspend fun add(id: String?, owner: String, origin: String, at: Long?, zone: String, inheritedId: String? = null) {
            if (id == null || id in masks || inheritedId in masks) return
            val content = db.processing().content(id) ?: return
            if (suppressions.any { suppressedTopic(content.body, it) }) {
                masks += id
                return
            }
            val scope = scopes[id]?.firstOrNull { it == project && it.isNotBlank() } ?: scopes[id]?.firstOrNull { it.isNotBlank() }.orEmpty()
            result += HistoryHit(id, owner, origin, content.body, at ?: content.createdAt, zone, scope)
        }
        events.filter { it.aggregateType == "AssistantTurn" && it.eventType == "AssistantTurnRequested" }.forEach { row ->
            val requested = eventJson.decodeFromString<TurnEvent>(row.payload) as TurnEvent.Requested
            val turn = db.conversations().turn(row.aggregateId) ?: return@forEach
            if (turn.status == "DELETED" || db.conversations().conversation(turn.conversationId)?.deleted != false) return@forEach
            val zone = requested.sourceZoneId ?: "UTC"
            add(turn.userContentId, turn.id, "USER_MESSAGE", null, zone)
            if (turn.status == "ANSWERED") add(turn.replyContentId, turn.id, "ASSISTANT_REPLY", null, zone, turn.userContentId)
        }
        db.recordings().all().filter { it.status != "DELETED" }.forEach { recording ->
            val text = db.processing().text(recording.id) ?: return@forEach
            add(text.transcriptContentId, recording.id, if (text.transcriptOrigin == "USER") "USER_TRANSCRIPT" else "MACHINE_TRANSCRIPT", recording.startedAt, recording.zone)
        }
        return result.distinctBy { it.contentId }
    }
}

/** Conservative literal topic aliases, not a claim of universal semantic erasure. */
private fun suppressedTopic(body: String, label: String): Boolean {
    val text = body.lowercase(java.util.Locale.ROOT)
    val tokens = label.split(" · ")
    val predicate = MemoryPredicate.entries.firstOrNull { it.label in tokens } ?: return false
    val aliases = when (predicate) {
        MemoryPredicate.COFFEE -> listOf("咖啡", "拿铁", "美式", "卡布奇诺", "冷萃", "coffee", "latte", "espresso", "cappuccino")
        MemoryPredicate.TEA -> listOf("茶", "tea", "matcha")
        MemoryPredicate.FOOD -> listOf("吃", "饮食", "食物", "口味", "food", "diet")
        MemoryPredicate.ANSWER_STYLE -> listOf("回复", "回答", "简洁", "简短", "详细", "交流", "answer", "response")
        MemoryPredicate.NAME -> listOf("我叫", "名字", "称呼", "name")
        MemoryPredicate.PROJECT, MemoryPredicate.TASK, MemoryPredicate.AGREEMENT -> listOf(tokens.drop(2).joinToString(" · ")).filter { it.isNotBlank() }
    }
    return aliases.any(text::contains)
}
