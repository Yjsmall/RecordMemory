package dev.local.record.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryAction { ADD, REINFORCE, SUPERSEDE, ASK_USER }

/** A small stable registry makes suppression independent of paraphrased fact values. */
enum class MemoryPredicate(val key: String, val label: String) {
    COFFEE("drink.coffee", "咖啡习惯"),
    TEA("drink.tea", "饮茶习惯"),
    FOOD("food.preference", "饮食偏好"),
    ANSWER_STYLE("communication.style", "交流方式"),
    NAME("identity.name", "称呼"),
    PROJECT("project.status", "项目状态"),
    TASK("task.status", "待办状态"),
    AGREEMENT("agreement", "约定")
}

@Serializable
data class MemorySource(
    val contentId: String,
    val conversationId: String = "",
    val turnId: String = "",
    val recordingId: String = "",
    val origin: String = "USER_MESSAGE",
    val evidence: String,
    val start: Int,
    val end: Int,
    val observedAt: Long,
    val zoneId: String?
)

/** Unknown structure remains null. Sources and private metadata live in deletable content. */
@Serializable
data class MemoryFact(
    val subject: String,
    val predicate: String,
    val scope: String = "",
    val validFrom: String? = null,
    val validUntil: String? = null,
    val sources: List<MemorySource> = emptyList(),
    val zoneId: String? = null
) {
    val key: String get() = memoryFingerprint(listOf(subject, predicate, scope).joinToString("\u001f"))

    fun effectiveAt(now: Long): Boolean {
        val zone = zoneId ?: sources.firstOrNull()?.zoneId ?: "UTC"
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate()
        return (validFrom == null || !date.isBefore(LocalDate.parse(validFrom))) &&
            (validUntil == null || date.isBefore(LocalDate.parse(validUntil)))
    }
}

@Serializable
data class MemoryChange(val action: MemoryAction = MemoryAction.ADD, val targetId: String? = null, val expectedVersion: Int? = null, val question: String = "")

fun validateMemoryFact(fact: MemoryFact) {
    require(fact.subject.isNotBlank() && fact.subject.length <= 80 && fact.subject == fact.subject.trim())
    require(fact.subject.none(Char::isISOControl) && fact.scope.none(Char::isISOControl))
    require(MemoryPredicate.entries.any { it.key == fact.predicate }) { "未知事实范围" }
    require(fact.scope.length <= 80 && fact.scope == fact.scope.trim())
    require(fact.predicate !in setOf("project.status", "task.status", "agreement") || fact.scope.isNotBlank()) { "项目、待办与约定需要明确范围" }
    require(fact.predicate in setOf("project.status", "task.status", "agreement") || fact.scope.isEmpty()) { "个人事实使用固定范围，不能改变范围绕过遗忘" }
    fact.validFrom?.let(LocalDate::parse)
    fact.validUntil?.let(LocalDate::parse)
    require(fact.validFrom == null || fact.validUntil == null || LocalDate.parse(fact.validFrom).isBefore(LocalDate.parse(fact.validUntil)))
}

fun MemoryItem.currentAt(now: Long) = status == MemoryStatus.CONFIRMED && text.isNotBlank() && (fact?.effectiveAt(now) != false)

fun MemoryItem.changeLabel(): String = when (change?.action) {
    MemoryAction.REINFORCE -> "补充依据"
    MemoryAction.SUPERSEDE -> "替代旧记忆"
    MemoryAction.ASK_USER -> "需要澄清"
    else -> if (status == MemoryStatus.CONFIRMED) {
        if (fact?.effectiveAt(System.currentTimeMillis()) == false) "当前未生效" else "已确认"
    } else {
        "待确认"
    }
}

fun MemoryItem.confirmable() = status == MemoryStatus.CANDIDATE && change?.action != MemoryAction.ASK_USER
