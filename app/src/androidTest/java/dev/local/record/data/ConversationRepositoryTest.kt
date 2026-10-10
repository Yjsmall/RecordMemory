package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationRepositoryTest {
    private lateinit var db: RecordDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var processing: ProcessingRepository
    private fun context(memories: List<MemoryReference> = emptyList()) = AssistantContext("provider", "测试服务", "model", "protocol", "prompt", memories, emptyList())

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        repository = ConversationRepository(db)
        processing = ProcessingRepository(db)
    }

    @After fun close() = db.close()

    @Test fun duplicateRequestsAndCancelledAttemptsDoNotPublish() = runTest {
        repository.create("c", 1)
        repository.request("t", "c", "喜欢安静", 2)
        repository.request("t", "c", "喜欢安静", 2)
        assertEquals(1, repository.turns("c").size)
        repository.start("t", context(), 3)
        repository.cancel("t", 4)
        assertFalse(repository.answer("t", 1, "旧回复", emptyList(), 5))
        repository.start("t", context(), 6)
        repository.cancel("t", 7, expectedAttempt = 1)
        repository.fail("t", 1, "OLD", 7)
        assertEquals(TurnStatus.RUNNING, repository.turn("t")?.status)
        assertTrue(repository.answer("t", 2, "新回复", emptyList(), 8))
        assertFalse(repository.answer("t", 2, "重复回复", emptyList(), 9))
        val before = repository.turn("t")
        RecordingRepository(db).rebuild()
        assertEquals(before, repository.turn("t"))
        assertTrue(db.processing().pending().isEmpty())
    }

    @Test fun deletionPurgesEveryAttemptAndOriginMemoryWithoutReplayResurrection() = runTest {
        repository.create("c", 1)
        repository.request("t", "c", "我喜欢安静", 2)
        repository.start("t", context(), 3)
        repository.fail("t", 1, "NETWORK", 4)
        repository.start("t", context(), 5)
        repository.answer("t", 2, "记忆待确认", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "我喜欢安静")), 6)
        val memory = processing.memories().single()
        processing.confirmMemory(memory.id, 7)
        repository.delete("c", 8)
        assertEquals(MemoryStatus.INVALIDATED, processing.memory(memory.id)?.status)
        assertTrue(db.query("SELECT body FROM contents", null).use { !it.moveToFirst() })
        RecordingRepository(db).rebuild()
        assertEquals(TurnStatus.DELETED, repository.turn("t")?.status)
        assertEquals("", repository.turn("t")?.userText)
        assertEquals("", repository.turn("t")?.reply)
        assertTrue(repository.conversations.first().isEmpty())
        assertFalse(repository.answer("t", 2, "迟到正文", emptyList(), 9))
    }

    @Test fun confirmedMemoryCorrectionInvalidatesInFlightAnswer() = runTest {
        repository.create("c", 1)
        repository.request("t1", "c", "我喜欢安静", 2)
        processing.proposeConversationMemories("t1", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "")), 3, explicit = true)
        val memory = processing.memories().single()
        repository.start("t1", context(listOf(MemoryReference(memory.id, memory.version))), 4)
        processing.correctMemory(memory.id, "现在喜欢热闹", 5)
        assertFalse(repository.answer("t1", 1, "旧偏好回复", emptyList(), 6))
        assertEquals("CONTEXT_CHANGED", repository.turn("t1")?.failure)
        assertEquals("", repository.turn("t1")?.reply)
    }

    @Test fun forgottenCandidateCannotBeAutomaticallyReintroducedFromAnotherConversation() = runTest {
        repository.create("c", 1)
        repository.request("t", "c", "我喜欢安静", 2)
        val candidate = MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "我喜欢安静")
        processing.proposeConversationMemories("t", listOf(candidate), 3)
        processing.forgetMemory(processing.memories().single().id, 4)
        repository.cancel("t", 5)
        repository.create("c2", 6)
        repository.request("t2", "c2", "我喜欢安静", 7)
        processing.proposeConversationMemories("t2", listOf(candidate), 8)
        assertEquals(1, processing.memories().size)
        processing.proposeConversationMemories("t2", listOf(candidate), 9, explicit = true)
        assertEquals(1, processing.memories().count { it.status == MemoryStatus.CONFIRMED })
    }

    @Test fun restartMarksRequestsInterruptedWithoutCreatingNetworkWork() = runTest {
        repository.create("c", 1)
        repository.request("t", "c", "hello", 2)
        repository.start("t", context(), 3)
        repository.recoverInterrupted(4)
        assertEquals(TurnStatus.FAILED, repository.turn("t")?.status)
        assertEquals("INTERRUPTED", repository.turn("t")?.failure)
        assertTrue(db.processing().pending().isEmpty())
        repository.start("t", context(), 5)
        assertEquals(2, repository.turn("t")?.attempt)
    }

    @Test fun concurrentRequestsAdmitOnlyOnePendingMessagePerConversation() = runTest {
        repository.create("c", 1)
        val one = async(Dispatchers.IO) { runCatching { repository.request("t1", "c", "one", 2) } }
        val two = async(Dispatchers.IO) { runCatching { repository.request("t2", "c", "two", 2) } }
        assertEquals(1, listOf(one.await(), two.await()).count { it.isSuccess })
        assertEquals(1, repository.turns("c").size)
    }

    @Test fun crossOriginMergeCannotLeakDeletedConversationMemory() = runTest {
        repository.create("c1", 1)
        repository.create("c2", 1)
        repository.request("t1", "c1", "喜欢安静", 2)
        repository.request("t2", "c2", "周末休息", 2)
        processing.proposeConversationMemories("t1", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "")), 3, explicit = true)
        processing.proposeConversationMemories("t2", listOf(MemoryDraft(MemoryKind.PREFERENCE, "周末休息", "")), 3, explicit = true)
        val first = processing.memories().first { it.sourceConversationId == "c1" }
        val second = processing.memories().first { it.sourceConversationId == "c2" }
        assertTrue(runCatching { processing.mergeMemory(first.id, second.id, 4) }.isFailure)
        repository.delete("c1", 5)
        assertEquals("周末休息", processing.memory(second.id)?.text)
        assertEquals("", processing.memory(first.id)?.text)
    }
}
