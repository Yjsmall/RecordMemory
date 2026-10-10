package dev.local.record.ai

import dev.local.record.data.MemoryDraft
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryKind

/** Deterministic eligibility only; normal commit must still validate source, conflict and suppression. */
object AutomaticMemoryPolicy {
    private val preferences = mapOf(
        "drink.coffee" to setOf("我喜欢喝咖啡", "我不喝咖啡", "我不喜欢喝咖啡", "我喜欢喝黑咖啡", "我喜欢喝美式咖啡"),
        "drink.tea" to setOf("我喜欢喝茶", "我不喝茶", "我不喜欢喝茶", "我喜欢喝绿茶", "我喜欢喝红茶"),
        "food.preference" to setOf("我喜欢吃面条", "我喜欢吃米饭", "我喜欢吃饺子", "我喜欢吃水果", "我喜欢吃蔬菜"),
        "communication.style" to setOf("我喜欢简短的回答", "我喜欢详细的回答", "我喜欢用中文交流", "我喜欢用英文交流")
    )
    private val projectScopes = setOf("录音", "笔记", "待办", "日历", "天气", "相册", "学习", "开源工具")
    private val projectTechnologies = setOf("Kotlin", "Java", "Compose", "Android")

    /** Only exact complete ordinary statements with an explicit ADD and unambiguous self identity. */
    fun eligible(draft: MemoryDraft, sourceText: String): Boolean {
        val fact = draft.fact ?: return false
        val change = draft.change ?: return false
        if (change.action != MemoryAction.ADD || change.targetId != null || change.expectedVersion != null || change.question.isNotEmpty()) return false
        if (fact.subject != "self" || fact.validFrom != null || fact.validUntil != null || fact.zoneId != null || fact.sources.isNotEmpty()) return false
        // A substring is insufficient: the rest could negate, quote, time-limit or expose sensitive content.
        if (sourceText.trim() != draft.evidence || draft.text != draft.evidence || draft.evidence.length !in 1..200) return false
        val statement = draft.evidence.removeSuffix("。").removeSuffix(".")
        return when (draft.type) {
            MemoryKind.PREFERENCE -> fact.scope.isEmpty() && statement in preferences[fact.predicate].orEmpty()
            MemoryKind.PROJECT ->
                fact.predicate == "project.status" && fact.scope in projectScopes &&
                    (statement == "我在开发${fact.scope}项目" || projectTechnologies.any { statement == "我的${fact.scope}项目使用$it" })
            else -> false
        }
    }
}
