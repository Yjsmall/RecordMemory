package dev.local.record.domain

import dev.local.record.ai.parseMemoryPlan
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKnowledgeTest {
    @Test fun boundedPlanRequiresStructureTargetsQuestionsAndKnownPredicates() {
        val plan = """{"schemaVersion":2,"items":[{"action":"SUPERSEDE","type":"preference","text":"我不喝咖啡","evidence":"我不喝咖啡","fact":{"subject":"self","predicate":"drink.coffee"},"targetId":"old","expectedVersion":2}]}"""
        val draft = parseMemoryPlan(plan, "我不喝咖啡").single()
        assertEquals(MemoryAction.SUPERSEDE, draft.change?.action)
        assertEquals("old", draft.change?.targetId)
        assertTrue(runCatching { parseMemoryPlan(plan.replace("drink.coffee", "made.up"), "我不喝咖啡") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan(plan.replace(",\"expectedVersion\":2", ""), "我不喝咖啡") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan(plan.replace("SUPERSEDE", "ASK_USER"), "我不喝咖啡") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan("""{"schemaVersion":1,"items":[]}""", "", allowLegacy = false) }.isFailure)
    }

    @Test fun expiredAndFutureFactsStayInHistoryButLeaveProfileAndRetrieval() {
        val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        val fact = MemoryFact("self", "drink.coffee", validFrom = "2026-10-01", validUntil = "2026-10-10")
        val item = MemoryItem("coffee", status = MemoryStatus.CONFIRMED, type = MemoryKind.PREFERENCE, text = "我喝咖啡", fact = fact)
        assertFalse(item.currentAt(now))
        assertTrue(personalMemoryProfile(listOf(item), now).items.isEmpty())
        assertTrue(personalMemoryProfile(listOf(item.copy(fact = fact.copy(validUntil = "2026-10-11"))), now).items.isNotEmpty())
        assertFalse(item.copy(fact = fact.copy(validFrom = "2026-10-11", validUntil = null)).currentAt(now))
        assertEquals("我喝咖啡", item.text)
    }

    @Test fun suppressionKeyIgnoresValueAndDateButSeparatesSubjectAndScope() {
        val fact = MemoryFact("self", "drink.coffee")
        assertEquals(fact.key, fact.copy(validFrom = "2026-10-10").key)
        assertFalse(fact.key == fact.copy(subject = "妈妈").key)
        assertFalse(fact.key == fact.copy(predicate = "drink.tea").key)
        assertFalse(fact.key == fact.copy(scope = "工作").key)
        assertTrue(runCatching { validateMemoryFact(MemoryFact("self", "project.status")) }.isFailure)
        assertTrue(runCatching { validateMemoryFact(fact.copy(validFrom = "2026-02-30")) }.isFailure)
    }

    @Test fun forgettingErasesMetadataAndExplicitRelearningNeverRestoresIt() {
        val item = MemoryItem("m", version = 2, text = "私密正文", evidence = "私密依据", fact = MemoryFact("self", "drink.coffee"))
        val forgotten = evolveMemory(item, MemoryEvent.Forgotten("hash", "scope-hash", "deletable-label"), "我 · 咖啡习惯", null)
        assertEquals("", forgotten.text)
        assertEquals(null, forgotten.fact)
        assertEquals("我 · 咖啡习惯", forgotten.suppressionLabel)
        val allowed = evolveMemory(forgotten, MemoryEvent.RelearningAllowed, null, null)
        assertEquals(MemoryStatus.FORGOTTEN, allowed.status)
        assertEquals("", allowed.suppressionKey)
        assertEquals("", allowed.suppressionLabel)
        assertEquals("", allowed.text)
    }
}
