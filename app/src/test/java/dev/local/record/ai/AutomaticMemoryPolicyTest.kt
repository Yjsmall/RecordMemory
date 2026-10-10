package dev.local.record.ai

import dev.local.record.data.MemoryDraft
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMemoryPolicyTest {
    @Test fun directOrdinaryPreferenceCanBeSavedWithoutInventingContent() {
        val draft = MemoryDraft(MemoryKind.PREFERENCE, "我喜欢喝咖啡", "我喜欢喝咖啡", MemoryFact("self", "drink.coffee"), MemoryChange())
        assertTrue(AutomaticMemoryPolicy.eligible(draft, "我喜欢喝咖啡"))
    }

    @Test fun syntheticSourcesMustMatchHandLabelledAutomaticSaveBoundary() {
        SyntheticMemoryCorpus.cases.forEach { case ->
            assertEquals(case.id, case.eligible, AutomaticMemoryPolicy.eligible(case.candidate, case.source))
        }
    }

    @Test fun everyUnvalidatedMetadataFieldKeepsCandidateInReview() {
        val draft = preference("我喜欢喝咖啡")
        val fact = requireNotNull(draft.fact)
        listOf(
            draft.copy(fact = fact.copy(validUntil = "2026-11-01")),
            draft.copy(fact = fact.copy(zoneId = "Asia/Shanghai")),
            draft.copy(fact = fact.copy(sources = listOf(MemorySource("source", evidence = draft.evidence, start = 0, end = draft.evidence.length, observedAt = 0, zoneId = null)))),
            draft.copy(change = MemoryChange(expectedVersion = 2))
        ).forEach { assertEquals(false, AutomaticMemoryPolicy.eligible(it, draft.evidence)) }
    }
}
