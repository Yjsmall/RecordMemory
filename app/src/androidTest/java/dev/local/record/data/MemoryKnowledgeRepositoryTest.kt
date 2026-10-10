package dev.local.record.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.personalMemoryProfile
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKnowledgeRepositoryTest {
    private val connection = AiConnection("memory-provider")
    private val binding = CapabilityBinding(AiCapability.MEMORY, connection.id, "synthetic-memory")
    private val coffee = MemoryFact("self", "drink.coffee")

    private class Fixture(val db: RecordDatabase) {
        val conversations = ConversationRepository(db)
        val processing = ProcessingRepository(db)
        val plans = MemoryPlanningRepository(db)

        suspend fun answered(id: String, message: String, now: Long = 1_791_633_600_000) {
            conversations.create("c:$id", now)
            conversations.request(id, "c:$id", message, now)
            conversations.start(id, AssistantContext("answer", "服务", "model", "openai-compatible-v1", "", emptyList(), emptyList()), now)
            conversations.answer(id, 1, "已收到你的说明。", emptyList(), now)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            Fixture(db).block()
        } finally {
            db.close()
        }
    }

    private suspend fun Fixture.plan(id: String, item: MemoryDraft, target: MemoryItem? = null): MemoryItem? {
        val task = plans.request(id, connection, binding, target?.let { listOf(MemoryReference(it.id, it.version)) }.orEmpty(), 10)
        assertTrue(plans.start(task.id, 11) != null)
        assertTrue(plans.complete(task.id, listOf(item), 12))
        return processing.memories().firstOrNull { it.sourceTurnId == id && it.visible }
    }

    private suspend fun Fixture.initial(): MemoryItem {
        answered("first", "我喜欢咖啡")
        val item = requireNotNull(plan("first", MemoryDraft(MemoryKind.PREFERENCE, "我喜欢咖啡", "我喜欢咖啡", coffee)))
        processing.confirmMemory(item.id, 20)
        return requireNotNull(processing.memory(item.id))
    }

    @Test fun replacementRequiresReviewAndAtomicallyRetiresOldFact() = fixture {
        val old = initial()
        answered("new", "我从今天开始不喝咖啡了")
        val candidate = requireNotNull(plan("new", MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我从今天开始不喝咖啡了", coffee, MemoryChange(MemoryAction.SUPERSEDE, old.id, old.version)), old))
        assertEquals(listOf(old.id), personalMemoryProfile(processing.memories()).items.map { it.id })
        processing.confirmMemory(candidate.id, 30)
        processing.confirmMemory(candidate.id, 31)
        assertEquals(MemoryStatus.SUPERSEDED, processing.memory(old.id)?.status)
        assertEquals(listOf(candidate.id), personalMemoryProfile(processing.memories()).items.map { it.id })
        val before = processing.memories()
        RecordingRepository(db).rebuild()
        assertEquals(before.associateBy { it.id }, processing.memories().associateBy { it.id })
        assertTrue(db.processing().pending().isEmpty())
        assertTrue(db.recordings().events().none { it.payload.contains("咖啡") })
    }

    @Test fun changedTargetRejectsConfirmationWithoutPartialReplacement() = fixture {
        val old = initial()
        answered("new", "我不喝咖啡了")
        val candidate = requireNotNull(plan("new", MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我不喝咖啡了", coffee, MemoryChange(MemoryAction.SUPERSEDE, old.id, old.version)), old))
        processing.correctMemory(old.id, "我偶尔喝咖啡", 30)
        val eventCount = db.recordings().events().size
        assertTrue(runCatching { processing.confirmMemory(candidate.id, 31) }.isFailure)
        assertEquals(eventCount, db.recordings().events().size)
        assertEquals(MemoryStatus.CANDIDATE, processing.memory(candidate.id)?.status)
        assertEquals(MemoryStatus.CONFIRMED, processing.memory(old.id)?.status)
    }

    @Test fun reinforcementRetainsIndependentSourcesAndWithdrawalRebuildsRemainingEvidence() = fixture {
        val old = initial()
        answered("second", "我一直爱喝咖啡")
        val candidate = requireNotNull(plan("second", MemoryDraft(MemoryKind.PREFERENCE, old.text, "我一直爱喝咖啡", coffee, MemoryChange(MemoryAction.REINFORCE, old.id, old.version)), old))
        processing.confirmMemory(candidate.id, 30)
        assertEquals(2, processing.memory(old.id)?.fact?.sources?.size)
        assertEquals(1, personalMemoryProfile(processing.memories()).items.size)
        conversations.delete("c:first", 40)
        val remaining = requireNotNull(processing.memory(old.id))
        assertEquals(MemoryStatus.CONFIRMED, remaining.status)
        assertEquals(listOf("second"), remaining.fact?.sources?.map { it.turnId })
        assertEquals("我一直爱喝咖啡", remaining.evidence)
        assertEquals("second", remaining.sourceTurnId)
        assertNull(db.processing().content(old.contentId))
        val before = processing.memories()
        RecordingRepository(db).rebuild()
        assertEquals(before.associateBy { it.id }, processing.memories().associateBy { it.id })
        conversations.delete("c:second", 50)
        assertEquals(MemoryStatus.INVALIDATED, processing.memory(old.id)?.status)
        assertNull(db.processing().content(remaining.contentId))
    }

    @Test fun semanticForgettingBlocksParaphrasesUntilExplicitRelearningWithoutRestoringText() = fixture {
        val old = initial()
        processing.forgetMemory(old.id, 30)
        assertEquals("我 · 咖啡习惯", processing.memory(old.id)?.suppressionLabel)
        answered("again", "喝咖啡是我的日常习惯")
        assertNull(plan("again", MemoryDraft(MemoryKind.PREFERENCE, "每天都喝咖啡", "喝咖啡是我的日常习惯", coffee)))
        assertTrue(personalMemoryProfile(processing.memories()).items.isEmpty())
        RecordingRepository(db).rebuild()
        assertEquals(coffee.key, processing.memory(old.id)?.suppressionKey)
        processing.allowMemoryRelearning(old.id, 40)
        assertEquals("", processing.memory(old.id)?.text)
        assertEquals("", processing.memory(old.id)?.suppressionLabel)
        answered("explicit-new", "咖啡是我的最爱")
        assertTrue(plan("explicit-new", MemoryDraft(MemoryKind.PREFERENCE, "很喜欢咖啡", "咖啡是我的最爱", coffee)) != null)
        val before = processing.memories()
        RecordingRepository(db).rebuild()
        assertEquals(before.associateBy { it.id }, processing.memories().associateBy { it.id })
        assertTrue(db.recordings().events().none { it.payload.contains("咖啡") })
    }

    @Test fun forgettingReplacementAlsoPurgesSupersededHistoryAndNewCandidates() = fixture {
        val old = initial()
        answered("new", "我不喝咖啡了")
        val newer = requireNotNull(plan("new", MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我不喝咖啡了", coffee, MemoryChange(MemoryAction.SUPERSEDE, old.id, old.version)), old))
        processing.confirmMemory(newer.id, 30)
        processing.forgetMemory(newer.id, 40)
        assertNull(db.processing().content(old.contentId))
        assertNull(db.processing().content(newer.contentId))
        assertTrue(processing.memories().all { it.status == MemoryStatus.FORGOTTEN && it.fact == null })
        RecordingRepository(db).rebuild()
        assertTrue(processing.memories().all { it.text.isEmpty() && it.fact == null && it.suppressionKey == coffee.key })
    }

    @Test fun addConflictBecomesQuestionAndCannotBypassReviewViaRememberButton() = fixture {
        val old = initial()
        answered("conflict", "我不喝咖啡了")
        val question = requireNotNull(plan("conflict", MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我不喝咖啡了", coffee)))
        assertEquals(MemoryAction.ASK_USER, question.change?.action)
        assertTrue(runCatching { processing.confirmMemory(question.id, 30) }.isFailure)
        assertTrue(runCatching { processing.proposeConversationMemories("conflict", listOf(MemoryDraft(MemoryKind.PREFERENCE, question.text, question.evidence)), 31, explicit = true) }.isFailure)
        assertEquals(MemoryStatus.CONFIRMED, processing.memory(old.id)?.status)
    }

    @Test fun malformedSourceScopeDatesAndTargetsRollbackWholePlan() = fixture {
        val old = initial()
        answered("bad", "我不喝咖啡了")
        val task = plans.request("bad", connection, binding, listOf(MemoryReference(old.id, old.version)), 30)
        plans.start(task.id, 31)
        val valid = MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我不喝咖啡了", coffee, MemoryChange(MemoryAction.SUPERSEDE, old.id, old.version))
        val badItems = listOf(
            valid.copy(fact = coffee.copy(subject = "妈妈")),
            valid.copy(fact = coffee.copy(scope = "家里")),
            valid.copy(fact = coffee.copy(validFrom = "2030-01-01")),
            valid.copy(change = MemoryChange(MemoryAction.SUPERSEDE, "unknown", 1)),
            valid.copy(change = MemoryChange(MemoryAction.REINFORCE, old.id, old.version))
        )
        badItems.forEach { bad ->
            val eventCount = db.recordings().events().size
            assertTrue(runCatching { plans.complete(task.id, listOf(valid, bad), 32) }.isFailure)
            assertEquals(eventCount, db.recordings().events().size)
            assertEquals(1, processing.memories().size)
        }
    }

    @Test fun relativeDateUsesOriginalMessageTimeAndZoneInsteadOfPlanningTime() = fixture {
        val observed = java.time.Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        answered("date", "我从今天开始不喝咖啡", observed)
        val task = plans.request("date", connection, binding, emptyList(), observed + 86_400_000)
        val input = requireNotNull(plans.start(task.id, observed + 86_400_000))
        val expected = java.time.Instant.ofEpochMilli(observed).atZone(java.time.ZoneId.of(input.sourceZoneId)).toLocalDate().toString()
        val draft = MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我从今天开始不喝咖啡", coffee.copy(validFrom = expected))
        assertTrue(plans.complete(task.id, listOf(draft), observed + 86_400_001))
        assertEquals(observed, processing.memories().single().fact?.sources?.single()?.observedAt)
        assertEquals(expected, processing.memories().single().fact?.validFrom)
        assertFalse(processing.memories().single().fact?.effectiveAt(observed - 86_400_000) ?: true)
    }

    @Test fun userCorrectionBecomesIndependentEvidenceAndSurvivesLaterSourceWithdrawal() = fixture {
        val old = initial()
        processing.correctMemory(old.id, "我偶尔喝咖啡", 30)
        val corrected = requireNotNull(processing.memory(old.id))
        assertEquals("USER_CORRECTION", corrected.fact?.sources?.single()?.origin)
        assertEquals(coffee.key, corrected.fact?.key)
        assertEquals("", corrected.sourceConversationId)
        assertNull(db.processing().content(old.contentId))
        conversations.delete("c:first", 31)
        assertEquals(MemoryStatus.CONFIRMED, processing.memory(old.id)?.status)
        answered("reinforce-correction", "我只是偶尔喝咖啡")
        val proposal = requireNotNull(plan("reinforce-correction", MemoryDraft(MemoryKind.PREFERENCE, corrected.text, "我只是偶尔喝咖啡", coffee, MemoryChange(MemoryAction.REINFORCE, corrected.id, corrected.version)), corrected))
        processing.confirmMemory(proposal.id, 40)
        conversations.delete("c:reinforce-correction", 50)
        assertTrue(db.processing().content(corrected.contentId) != null)
        val remaining = requireNotNull(processing.memory(old.id))
        assertEquals(listOf("USER_CORRECTION"), remaining.fact?.sources?.map { it.origin })
        RecordingRepository(db).rebuild()
        assertEquals(remaining, processing.memory(old.id))
        processing.forgetMemory(old.id, 60)
        assertNull(db.processing().content(corrected.contentId))
        assertNull(db.processing().content(remaining.contentId))
    }

    @Test fun otherSubjectCannotBecomeSelfAndForgettingDoesNotSuppressMothersFacts() = fixture {
        val old = initial()
        processing.forgetMemory(old.id, 30)
        answered("mother", "我妈妈喜欢咖啡")
        val task = plans.request("mother", connection, binding, emptyList(), 31)
        plans.start(task.id, 32)
        val wrong = MemoryDraft(MemoryKind.PREFERENCE, "我喜欢咖啡", "我妈妈喜欢咖啡", coffee)
        assertTrue(runCatching { plans.complete(task.id, listOf(wrong), 33) }.isFailure)
        assertTrue(plans.complete(task.id, listOf(wrong.copy(text = "妈妈喜欢咖啡", fact = coffee.copy(subject = "妈妈"))), 34))
        val candidate = processing.memories().single { it.status == MemoryStatus.CANDIDATE }
        processing.confirmMemory(candidate.id, 35)
        assertEquals("妈妈", processing.memory(candidate.id)?.fact?.subject)
        assertEquals(1, personalMemoryProfile(processing.memories()).items.size)
    }

    @Test fun futureReplacementCannotRetireCurrentFactBeforeItTakesEffect() = fixture {
        val old = initial()
        val observed = java.time.Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        answered("future", "我从2030-01-01开始不喝咖啡", observed)
        val future = requireNotNull(plan("future", MemoryDraft(MemoryKind.PREFERENCE, "我不喝咖啡", "我从2030-01-01开始不喝咖啡", coffee.copy(validFrom = "2030-01-01"), MemoryChange(MemoryAction.SUPERSEDE, old.id, old.version)), old))
        assertTrue(runCatching { processing.confirmMemory(future.id, observed) }.isFailure)
        assertEquals(MemoryStatus.CONFIRMED, processing.memory(old.id)?.status)
        assertEquals(MemoryStatus.CANDIDATE, processing.memory(future.id)?.status)
    }
}
