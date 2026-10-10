package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.LearningFeedback
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.memoryFeedback
import dev.local.record.domain.planningFailureLabel
import kotlinx.coroutines.flow.map

/** Reading recent changes neither enqueues tasks nor restores deleted private bodies. */
class LearningFeedbackRepository(private val db: RecordDatabase) {
    val changes = db.recordings().observeEvents().map { recent() }

    suspend fun recent(): List<LearningFeedback> = db.withTransaction {
        val events = db.recordings().events()
        val memories = db.processing().memories().map(MemoryRow::domain)
        val last = events.filter { it.aggregateType in setOf("Memory", "MemoryPlanning") }.groupBy { it.aggregateId }.mapValues { it.value.last() }
        val recentIds = last.values.sortedByDescending { it.globalPosition }.take(50).map { it.aggregateId }.toSet()
        val memoryChanges = memories.filter { it.id in recentIds }.mapNotNull { memory ->
            val event = last[memory.id] ?: return@mapNotNull null
            // Confirmation clears change from the projection, but its retained body holds the plan.
            val stored = if (memory.visible) db.processing().content(memory.contentId)?.body?.let { runCatching { eventJson.decodeFromString<MemoryBody>(it) }.getOrNull() } else null
            val display = memory.copy(change = memory.change ?: stored?.change)
            val previous = display.change?.targetId?.let { id -> memories.firstOrNull { it.id == id } }
            memoryFeedback(display, event.eventType, event.occurredAt, previous)
        }
        val plans = MemoryPlanningRepository(db)
        val taskChanges = db.memoryPlanning().all().filter { it.id in recentIds }.mapNotNull { row ->
            val event = last[row.id] ?: return@mapNotNull null
            val task = row.domain()
            val input = plans.input(task)
            LearningFeedback(
                id = task.id,
                occurredAt = event.occurredAt,
                label = "记忆整理",
                status = when (task.status) {
                    MemoryPlanningStatus.REQUESTED -> "已排队"
                    MemoryPlanningStatus.RUNNING -> "正在整理"
                    MemoryPlanningStatus.COMPLETED -> "整理完成 · ${task.candidateCount} 项"
                    MemoryPlanningStatus.FAILED -> "整理失败"
                    MemoryPlanningStatus.CANCELLED -> "已取消"
                },
                source = if (input == null) {
                    "来源已清理"
                } else {
                    "聊天原文 · ${if (input.historyAuthorized) {
                        "所选历史"
                    } else if (input.automatic) {
                        "自动整理"
                    } else {
                        "手动整理"
                    }} · 每条最多 1 次调用"
                },
                failure = planningFailureLabel(task.failure),
                taskId = task.id,
                model = input?.let { "${it.connection.name} · ${it.binding.model}" }.orEmpty(),
                sourceContentId = input?.sourceContentId?.takeIf { HistorySearchRepository(db).read(it) != null }
            )
        }
        (memoryChanges + taskChanges).sortedByDescending { last[it.id]?.globalPosition ?: 0 }.take(50)
    }
}
