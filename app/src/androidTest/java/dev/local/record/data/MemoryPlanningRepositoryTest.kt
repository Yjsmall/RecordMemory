package dev.local.record.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryStatus
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
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

    private suspend fun answered(conversations: ConversationRepository, turn: String = "t", conversation: String = "c") {
        conversations.create(conversation, 1)
        conversations.request(turn, conversation, "我喜欢咖啡", 2)
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
}
