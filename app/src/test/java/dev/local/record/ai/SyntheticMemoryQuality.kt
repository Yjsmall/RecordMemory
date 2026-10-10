package dev.local.record.ai

import dev.local.record.data.MemoryDraft
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryKind

/** Hand-labelled, invented examples. These are not captured provider outputs. */
internal data class SyntheticMemoryCase(
    val id: String,
    val source: String,
    val candidate: MemoryDraft,
    val eligible: Boolean,
    val gold: List<MemoryDraft> = emptyList()
)

internal fun preference(text: String, predicate: String = "drink.coffee") =
    MemoryDraft(MemoryKind.PREFERENCE, text, text, MemoryFact("self", predicate), MemoryChange())

internal fun project(text: String, scope: String) =
    MemoryDraft(MemoryKind.PROJECT, text, text, MemoryFact("self", "project.status", scope), MemoryChange())

internal object SyntheticMemoryCorpus {
    private val coffee = preference("我喜欢喝咖啡")
    private fun direct(id: String, draft: MemoryDraft, eligible: Boolean = true) = SyntheticMemoryCase(id, draft.evidence, draft, eligible, listOf(draft))
    private fun review(id: String, source: String, draft: MemoryDraft = coffee) = SyntheticMemoryCase(id, source, draft, false)

    val cases = listOf(
        direct("coffee", coffee),
        direct("coffee-negative", preference("我不喝咖啡")),
        direct("tea", preference("我喜欢喝茶", "drink.tea")),
        direct("food", preference("我喜欢吃面条", "food.preference")),
        direct("style", preference("我喜欢简短的回答", "communication.style")),
        direct("project", project("我在开发录音项目", "录音")),
        direct("project-tech", project("我的笔记项目使用Kotlin", "笔记")),
        direct("punctuation", preference("我喜欢喝咖啡。")),
        direct("ordinary-unlisted", preference("我偏爱四川家常菜", "food.preference"), eligible = false),
        direct("project-unlisted", project("我在开发晨光项目", "晨光"), eligible = false),
        review("quote", "他说：我喜欢喝咖啡"),
        review("quote-marks", "“我喜欢喝咖啡”"),
        review("hypothetical", "如果我喜欢喝咖啡，请提醒我"),
        review("temporary", "今天我喜欢喝咖啡"),
        review("other-person", "妈妈喜欢喝咖啡", coffee.copy(fact = MemoryFact("妈妈", "drink.coffee"))),
        review("multiple-subjects", "我喜欢喝咖啡，我的朋友喜欢喝茶"),
        review("sensitive-source", "我喜欢喝咖啡。我的病历在医院"),
        review("sensitive-project", "我在开发抑郁症诊断项目", project("我在开发抑郁症诊断项目", "抑郁症诊断")),
        review("credential-project", "我的密码项目使用Kotlin", project("我的密码项目使用Kotlin", "密码")),
        review("bank-project", "我在开发银行卡123456项目", project("我在开发银行卡123456项目", "银行卡123456")),
        review("conflict", "我喜欢喝咖啡，但我不喜欢喝咖啡"),
        review("correction", "更正：我喜欢喝咖啡"),
        review("forget", "忘记我喜欢喝咖啡"),
        review("uncertain", "我可能喜欢喝咖啡"),
        review("question", "我喜欢喝咖啡吗？"),
        review("negated-quote", "不要记录我喜欢喝咖啡"),
        review("evidence-truncation", "我喜欢喝咖啡，因为最近熬夜"),
        review("invented-body", coffee.evidence, coffee.copy(text = "我喜欢喝咖啡并且每天喝三杯")),
        review("invented-evidence", coffee.evidence, coffee.copy(evidence = "我每天喝咖啡")),
        review("wrong-predicate", coffee.evidence, coffee.copy(fact = MemoryFact("self", "drink.tea"))),
        review("wrong-type", coffee.evidence, coffee.copy(type = MemoryKind.PROJECT)),
        review("unknown-structure", coffee.evidence, coffee.copy(fact = null)),
        review("legacy-action", coffee.evidence, coffee.copy(change = null)),
        review("unknown-predicate", coffee.evidence, coffee.copy(fact = MemoryFact("self", "unknown"))),
        review("dated", coffee.evidence, coffee.copy(fact = MemoryFact("self", "drink.coffee", validFrom = "2026-10-10"))),
        review("scoped-preference", coffee.evidence, coffee.copy(fact = MemoryFact("self", "drink.coffee", scope = "工作"))),
        review("project-scope-paraphrase", "我在开发录音项目", project("我在开发录音项目", "音频")),
        review("targeted-add", coffee.evidence, coffee.copy(change = MemoryChange(targetId = "old", expectedVersion = 2))),
        review("add-question", coffee.evidence, coffee.copy(change = MemoryChange(question = "真的吗？"))),
        review("reinforce", coffee.evidence, coffee.copy(change = MemoryChange(MemoryAction.REINFORCE, "old", 2))),
        review("supersede", coffee.evidence, coffee.copy(change = MemoryChange(MemoryAction.SUPERSEDE, "old", 2))),
        review("clarify", coffee.evidence, coffee.copy(change = MemoryChange(MemoryAction.ASK_USER, question = "真的吗？"))),
        review("empty-source", ""),
        review("hidden-controls", "我喜欢喝咖啡\u200b"),
        direct("english-unlisted", preference("I like coffee"), eligible = false)
    )

    // Candidate corruption cases reuse legitimate source texts. A model must see each source
    // once with its independent source-level gold, not the corrupt candidate's review label.
    val dialogueCases = cases.distinctBy { it.source }
}

internal data class MemoryQualityObservation(
    val caseId: String,
    val saved: List<MemoryDraft>,
    val calls: Int = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null
)

internal data class MemoryQualityScore(
    val falseSaves: Int,
    val misses: Int,
    val wrongOverwrites: Int,
    val matched: Int,
    val expected: Int,
    val calls: Int,
    val inputTokens: Long?,
    val outputTokens: Long?
) {
    val recall: Double get() = if (expected == 0) 0.0 else matched.toDouble() / expected
}

/** Score saved outputs against independent gold, matching identity as well as body. */
internal fun scoreMemoryQuality(cases: List<SyntheticMemoryCase>, observations: List<MemoryQualityObservation>): MemoryQualityScore {
    require(cases.map { it.id }.distinct().size == cases.size)
    require(observations.map { it.caseId }.distinct().size == observations.size)
    require(observations.all { observed -> cases.any { it.id == observed.caseId } })
    require(observations.all { it.calls >= 0 && (it.inputTokens == null || it.inputTokens >= 0) && (it.outputTokens == null || it.outputTokens >= 0) })
    var matched = 0
    var falseSaves = 0
    var wrongOverwrites = 0
    cases.forEach { case ->
        val remaining = case.gold.toMutableList()
        observations.firstOrNull { it.caseId == case.id }?.saved.orEmpty().forEach { saved ->
            val overwrites = saved.change?.action != MemoryAction.ADD || saved.change.targetId != null || saved.change.expectedVersion != null
            if (overwrites) wrongOverwrites++
            val match = remaining.indexOfFirst { gold ->
                !overwrites && saved.type == gold.type && saved.text == gold.text && saved.evidence == gold.evidence && saved.fact?.subject == gold.fact?.subject &&
                    saved.fact?.predicate == gold.fact?.predicate && saved.fact?.scope == gold.fact?.scope &&
                    saved.fact?.validFrom == gold.fact?.validFrom && saved.fact?.validUntil == gold.fact?.validUntil
            }
            if (match < 0) {
                falseSaves++
            } else {
                matched++
                remaining.removeAt(match)
            }
        }
    }
    val expected = cases.sumOf { it.gold.size }
    return MemoryQualityScore(
        falseSaves,
        expected - matched,
        wrongOverwrites,
        matched,
        expected,
        observations.sumOf { it.calls },
        if (observations.all { it.inputTokens != null }) observations.sumOf { requireNotNull(it.inputTokens) } else null,
        if (observations.all { it.outputTokens != null }) observations.sumOf { requireNotNull(it.outputTokens) } else null
    )
}
