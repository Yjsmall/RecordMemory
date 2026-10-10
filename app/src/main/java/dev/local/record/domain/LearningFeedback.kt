package dev.local.record.domain

/** Current retained content paired with its latest business change, never a private audit copy. */
data class LearningFeedback(
    val id: String,
    val occurredAt: Long,
    val label: String,
    val status: String,
    val text: String = "",
    val previousText: String = "",
    val source: String = "",
    val failure: String = "",
    val memoryId: String? = null,
    val taskId: String? = null,
    val model: String = "",
    val evidence: String = "",
    val sourceContentId: String? = null
)

fun memoryFeedback(memory: MemoryItem, eventType: String, occurredAt: Long, previous: MemoryItem?): LearningFeedback {
    val retained = memory.visible || memory.status == MemoryStatus.SUPERSEDED
    val status = when (memory.status) {
        MemoryStatus.CANDIDATE -> if (memory.change?.action == MemoryAction.ASK_USER) "需要澄清" else "待审核"
        MemoryStatus.CONFIRMED -> "已保存"
        MemoryStatus.FORGOTTEN -> "已忘记"
        MemoryStatus.INVALIDATED -> "来源失效"
        MemoryStatus.MERGED -> "已合并"
        MemoryStatus.SUPERSEDED -> "已被替代"
    }
    val label = when {
        !retained -> status
        memory.change?.action == MemoryAction.SUPERSEDE -> "替代旧记忆"
        memory.change?.action == MemoryAction.REINFORCE -> "补充依据"
        eventType == "MemoryCorrected" -> "纠正记忆"
        memory.change?.action == MemoryAction.ASK_USER -> "需要澄清"
        else -> "新增记忆"
    }
    return LearningFeedback(
        memory.id, occurredAt, label, status,
        text = if (retained) memory.text else "",
        previousText = if (retained && previous != null && (previous.visible || previous.status == MemoryStatus.SUPERSEDED)) previous.text else "",
        source = when {
            !retained -> ""
            memory.fact?.sources?.firstOrNull()?.origin == "USER_CORRECTION" -> "用户纠正"
            memory.sourceTurnId.isNotEmpty() -> "聊天原文"
            memory.sourceRecordingId.isNotEmpty() -> "录音转写"
            else -> "手动保存"
        },
        memoryId = memory.id,
        evidence = if (retained) memory.evidence else "",
        sourceContentId = memory.sourceContentId.takeIf { retained && it.isNotBlank() && memory.fact?.sources?.firstOrNull()?.origin != "USER_CORRECTION" }
    )
}

fun planningFailureLabel(reason: String?): String = when (reason) {
    "SOURCE_CHANGED" -> "来源或记忆已变化，请重新选择"
    "INTERRUPTED" -> "整理已中断，可能已计费；请明确重试"
    "TIMEOUT" -> "整理超时，可能已计费；请明确重试"
    "NETWORK" -> "网络中断，可能已计费；请明确重试"
    "FORMAT_OR_CONFIG" -> "模型输出或配置不兼容，请核对后重试"
    "BUDGET_EXCEEDED", "DAILY_BUDGET", "DAILY_LIMIT" -> "已达到调用上限"
    "PROVIDER" -> "服务未完成整理，请核对后重试"
    null -> ""
    else -> "整理失败：${reason.take(80)}"
}
