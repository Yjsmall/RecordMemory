package dev.local.record.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryStatus
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPlanningRepositoryTest {
    private val connection = AiConnection("memory-provider")
    private val binding = CapabilityBinding(AiCapability.MEMORY, "memory-provider", "synthetic-memory")
    private val item = MemoryDraft(MemoryKind.PREFERENCE, "喜欢咖啡", "我喜欢咖啡")

    private suspend fun answered(conversations: ConversationRepository, turn: String = "t", conversation: String = "c", text: String = "我喜欢咖啡") {
        conversations.create(conversation, 1)
        conversations.request(turn, conversation, text, 2)
        conversations.start(turn, AssistantContext("answer", "回答服务", "answer-model", "openai-compatible-v1", "", emptyList(), emptyList()), 3)
        conversations.answer(turn, 1, "我们可以聊聊你的偏好。", emptyList(), 4)
    }

    @Test fun candidateCommitAndTaskCompletionAreAtomicIdempotentAndReplayable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val processing = ProcessingRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations)
            val task = plans.request("t", connection, binding, emptyList(), 5)
            assertEquals(task.id, plans.request("t", connection, binding, emptyList(), 6).id)
            assertTrue(plans.start(task.id, 7) != null)
            assertTrue(runCatching { plans.complete(task.id, listOf(item.copy(evidence = "虚构的证据")), 8) }.isFailure)
            assertEquals(MemoryPlanningStatus.RUNNING, plans.task(task.id)?.status)
            assertTrue(processing.memories().isEmpty())
            assertTrue(plans.complete(task.id, listOf(item), 9))
            assertFalse(plans.complete(task.id, listOf(item), 10))
            val memory = processing.memories().single()
            assertEquals(MemoryStatus.CANDIDATE, memory.status)
            assertEquals("t", memory.sourceTurnId)
            val before = plans.task(task.id)
            RecordingRepository(db).rebuild()
            assertEquals(before, plans.task(task.id))
            assertEquals(memory, processing.memories().single())
            assertTrue(db.processing().pending().isEmpty())
            assertTrue(db.recordings().events().none { it.payload.contains("喜欢咖啡") })
        } finally {
            db.close()
        }
    }

    @Test fun forgottenSourceCancelsPlanningPurgesSnapshotAndCannotBeRelearnedFromSameMessage() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val processing = ProcessingRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations)
            processing.proposeConversationMemories("t", listOf(item), 5, explicit = true)
            val memory = processing.memories().single()
            val task = plans.request("t", connection, binding, emptyList(), 6)
            plans.start(task.id, 7)
            processing.forgetMemory(memory.id, 8)
            assertFalse(plans.complete(task.id, listOf(item.copy(text = "爱喝咖啡")), 9))
            assertNull(plans.input(requireNotNull(plans.task(task.id))))
            assertTrue(runCatching { plans.request("t", connection, binding, emptyList(), 10) }.isFailure)
            RecordingRepository(db).rebuild()
            assertEquals(MemoryStatus.FORGOTTEN, processing.memories().single().status)
            assertEquals(MemoryPlanningStatus.FAILED, plans.task(task.id)?.status)
        } finally {
            db.close()
        }
    }

    @Test fun deletionAndProcessInterruptionNeverPublishOrRequeuePaidWork() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations)
            val deleted = plans.request("t", connection, binding, emptyList(), 5)
            plans.start(deleted.id, 6)
            conversations.delete("c", 7)
            assertFalse(plans.complete(deleted.id, listOf(item), 8))
            assertNull(plans.input(requireNotNull(plans.task(deleted.id))))
            answered(conversations, "t2", "c2")
            val interrupted = plans.request("t2", connection, binding, emptyList(), 9)
            plans.start(interrupted.id, 10)
            plans.recoverInterrupted(11)
            assertEquals("INTERRUPTED", plans.task(interrupted.id)?.failure)
            RecordingRepository(db).rebuild()
            assertTrue(db.processing().pending().isEmpty())
            assertTrue(ProcessingRepository(db).memories().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun automaticRequestsAreUniqueAcrossConcurrentCallbacksFailureAndReplay() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations)
            val tasks = coroutineScope {
                List(8) { async { plans.request("t", connection, binding, emptyList(), 5, automatic = true) } }.map { it.await() }
            }
            assertEquals(1, tasks.map { it.id }.distinct().size)
            val task = tasks.first()
            assertTrue(requireNotNull(plans.input(task)).automatic)
            plans.start(task.id, 6)
            plans.recoverInterrupted(6)
            assertEquals(task.id, plans.request("t", connection, binding, emptyList(), 7, automatic = true).id)
            RecordingRepository(db).rebuild()
            assertEquals(1, db.memoryPlanning().all().size)
            assertTrue(requireNotNull(plans.input(requireNotNull(plans.task(task.id)))).automatic)
            assertEquals(MemoryPlanningStatus.FAILED, plans.task(task.id)?.status)
            val manual = plans.request("t", connection, binding, emptyList(), 8)
            assertTrue(manual.id != task.id)
            assertFalse(requireNotNull(plans.input(manual)).automatic)
        } finally {
            db.close()
        }
    }

    @Test fun disablingAutomaticPlanningCancelsOnlyItsDurablePendingTasks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations, "auto", "auto-conversation")
            answered(conversations, "manual", "manual-conversation")
            val automatic = plans.request("auto", connection, binding, emptyList(), 5, automatic = true)
            val manual = plans.request("manual", connection, binding, emptyList(), 5)
            plans.start(automatic.id, 6)
            plans.cancelAutomatic(7)
            assertEquals(MemoryPlanningStatus.CANCELLED, plans.task(automatic.id)?.status)
            assertEquals(MemoryPlanningStatus.REQUESTED, plans.task(manual.id)?.status)
            assertFalse(plans.complete(automatic.id, listOf(item), 8))
            assertTrue(ProcessingRepository(db).memories().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun recoveryKeepsCommittedRequestsButNeverRetriesAnUncertainAttempt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations, "queued", "queued-conversation")
            answered(conversations, "running", "running-conversation")
            val queued = plans.request("queued", connection, binding, emptyList(), 5)
            val running = plans.request("running", connection, binding, emptyList(), 6)
            plans.start(running.id, 7)
            plans.recoverInterrupted(8)
            assertEquals(MemoryPlanningStatus.REQUESTED, plans.task(queued.id)?.status)
            assertEquals("INTERRUPTED", plans.task(running.id)?.failure)
            RecordingRepository(db).rebuild()
            assertEquals(listOf(queued.id), plans.requested().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test fun historicalBatchIsFiniteAtomicAndIdempotentEvenAfterFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations, "first", "first-conversation")
            answered(conversations, "second", "second-conversation")
            assertEquals(2, plans.requestHistory(listOf("first", "second", "first"), connection, binding, emptyMap(), 5))
            val tasks = plans.requested()
            assertTrue(tasks.all { requireNotNull(plans.input(it)).historyAuthorized })
            plans.start(tasks.first().id, 6)
            plans.fail(tasks.first().id, "NETWORK", 7)
            assertEquals(0, plans.requestHistory(listOf("second", "first"), connection, binding, emptyMap(), 8))
            assertTrue(runCatching { plans.requestHistory(List(11) { "source-$it" }, connection, binding, emptyMap(), 9) }.isFailure)
            assertEquals(2, db.memoryPlanning().all().size)
        } finally {
            db.close()
        }
    }

    @Test fun automaticBudgetCountsPaidAttemptsRatherThanSuccessfulResults() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            repeat(21) { index ->
                val turnId = "turn-$index"
                answered(conversations, turnId, "conversation-$index")
                val task = plans.request(turnId, connection, binding, emptyList(), 5, automatic = true)
                val input = plans.start(task.id, 6)
                if (index < 20) {
                    assertTrue(input != null)
                    plans.fail(task.id, "NETWORK", 7)
                } else {
                    assertNull(input)
                    assertEquals("DAILY_LIMIT", plans.task(task.id)?.failure)
                }
            }
            answered(conversations, "next-day", "next-day-conversation")
            val nextDay = plans.request("next-day", connection, binding, emptyList(), 86_400_001, automatic = true)
            assertTrue(plans.start(nextDay.id, 86_400_002) != null)
        } finally {
            db.close()
        }
    }

    @Test fun automaticConfirmationUsesNormalEventsOnlyForEligibleNewAdds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations, text = "我喜欢喝咖啡")
            val task = plans.request("t", connection, binding, emptyList(), 5)
            plans.start(task.id, 6)
            val direct = MemoryDraft(MemoryKind.PREFERENCE, "我喜欢喝咖啡", "我喜欢喝咖啡", MemoryFact("self", "drink.coffee"), MemoryChange())
            assertTrue(plans.complete(task.id, listOf(direct), 7, autoConfirm = true))
            assertEquals(MemoryStatus.CONFIRMED, ProcessingRepository(db).memories().single().status)
            assertEquals(1, db.recordings().events().count { it.eventType == "MemoryConfirmed" })
            RecordingRepository(db).rebuild()
            assertEquals(MemoryStatus.CONFIRMED, ProcessingRepository(db).memories().single().status)
        } finally {
            db.close()
        }
    }

    @Test fun automaticConfirmationNeverConfirmsAnAddConvertedToConflictReview() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            answered(conversations, "first", "first-conversation", "我喜欢喝咖啡")
            answered(conversations, "second", "second-conversation", "我喜欢喝咖啡")
            val direct = MemoryDraft(MemoryKind.PREFERENCE, "我喜欢喝咖啡", "我喜欢喝咖啡", MemoryFact("self", "drink.coffee"), MemoryChange())
            val first = plans.request("first", connection, binding, emptyList(), 5)
            plans.start(first.id, 6)
            plans.complete(first.id, listOf(direct), 7, autoConfirm = true)
            val second = plans.request("second", connection, binding, emptyList(), 8)
            plans.start(second.id, 9)
            assertTrue(plans.complete(second.id, listOf(direct), 10, autoConfirm = true))
            val memories = ProcessingRepository(db).memories()
            assertEquals(1, memories.count { it.status == MemoryStatus.CONFIRMED })
            assertEquals(1, memories.count { it.status == MemoryStatus.CANDIDATE })
            assertEquals(dev.local.record.domain.MemoryAction.ASK_USER, memories.single { it.status == MemoryStatus.CANDIDATE }.change?.action)
        } finally {
            db.close()
        }
    }
}
