package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryEvent
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import dev.local.record.domain.evolveMemory
import dev.local.record.domain.memoryFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun forgettingPurgesHistoricAndTransitiveRepliesWhileKeepingUserMessagesAndUnrelatedReplies() = runTest {
        repository.create("c", 1)
        repository.request("source", "c", "我喜欢咖啡", 2)
        repository.start("source", context(), 3)
        repository.answer("source", 1, "咖啡相关回复", emptyList(), 4)
        processing.proposeConversationMemories("source", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢咖啡", "我喜欢咖啡")), 5, explicit = true)
        val memory = processing.memories().single()
        val refs = listOf(MemoryReference(memory.id, memory.version))
        repository.request("derived", "c", "去哪里", 6)
        val first = repository.start("derived", context(refs).copy(prompt = "旧私密提示词"), 7)
        repository.fail("derived", 1, "NETWORK", 8)
        val second = repository.start("derived", context(refs), 9)
        repository.answer("derived", 2, "咖啡派生回复", emptyList(), 10)
        repository.request("transitive", "c", "还有呢", 11)
        val third = repository.start("transitive", context().copy(historyTurnIds = listOf("derived")), 12)
        repository.answer("transitive", 1, "继续引用咖啡回复", emptyList(), 13)
        repository.create("unrelated", 14)
        repository.request("keep", "unrelated", "聊聊天气", 15)
        repository.start("keep", context(), 16)
        repository.answer("keep", 1, "天气相关回复", emptyList(), 17)
        val replyIds = listOf("source", "derived", "transitive").map { requireNotNull(repository.turn(it)?.replyContentId) }
        processing.forgetMemory(memory.id, 18)
        processing.forgetMemory(memory.id, 19)
        listOfNotNull(first.contextContentId, second.contextContentId, third.contextContentId).plus(replyIds).forEach { assertNull(db.processing().content(it)) }
        assertEquals("我喜欢咖啡", repository.turn("source")?.userText)
        assertEquals("天气相关回复", repository.turn("keep")?.reply)
        assertEquals("CONTEXT_WITHDRAWN", repository.turn("transitive")?.failure)
        assertFalse(repository.answer("transitive", 1, "迟到私密回复", emptyList(), 20))
        val before = repository.turns("c")
        RecordingRepository(db).rebuild()
        assertEquals(before, repository.turns("c"))
        assertTrue(before.all { it.reply.isEmpty() })
        assertTrue(db.processing().pending().isEmpty())
    }

    @Test fun deletedConversationInvalidatesDependentRepliesEvenWithoutMemoryCards() = runTest {
        repository.create("source", 1)
        repository.request("t1", "source", "私密原文", 2)
        repository.start("t1", context(), 3)
        repository.answer("t1", 1, "私密回复", emptyList(), 4)
        repository.create("target", 5)
        repository.request("t2", "target", "根据之前的内容", 6)
        repository.start("t2", context().copy(historyTurnIds = listOf("t1")), 7)
        repository.answer("t2", 1, "派生私密回复", emptyList(), 8)
        repository.delete("source", 9)
        assertEquals("CONTEXT_WITHDRAWN", repository.turn("t2")?.failure)
        assertNull(db.processing().content("t2:reply:1"))
        RecordingRepository(db).rebuild()
        assertEquals("", repository.turn("t2")?.reply)
        assertEquals("根据之前的内容", repository.turn("t2")?.userText)
    }

    @Test fun withdrawalCannotBeBypassedByAnOldSnapshotAndRecoveryDoesNotRemoveAnAuthorizedRetry() = runTest {
        repository.create("c", 1)
        repository.request("t", "c", "喜欢安静", 2)
        val original = requireNotNull(repository.turn("t"))
        processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "喜欢安静")), 3, explicit = true)
        val memory = processing.memories().single()
        val oldContext = context(listOf(MemoryReference(memory.id, memory.version)))
        repository.start("t", oldContext, 4)
        processing.forgetMemory(memory.id, 5)
        assertTrue(runCatching { repository.start("t", context(), 6, expectedVersion = original.version) }.isFailure)
        assertTrue(runCatching { repository.start("t", oldContext, 6) }.isFailure)
        repository.start("t", context(), 7)
        repository.answer("t", 2, "用户主动重试后的新回复", emptyList(), 8)
        repository.purgeWithdrawnMemoryContent(9)
        assertEquals("用户主动重试后的新回复", repository.turn("t")?.reply)
        RecordingRepository(db).rebuild()
        assertEquals("用户主动重试后的新回复", repository.turn("t")?.reply)
    }

    @Test fun upgradeRecoveryPurgesDerivedContentLeftByThePreviousVersionExactlyOnce() = runTest {
        repository.create("c", 1)
        repository.request("t1", "c", "喜欢安静", 2)
        repository.start("t1", context(), 3)
        repository.answer("t1", 1, "原始回复", emptyList(), 4)
        processing.proposeConversationMemories("t1", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静", "喜欢安静")), 5, explicit = true)
        val memory = processing.memories().single()
        repository.request("t2", "c", "如何安排", 6)
        val running = repository.start("t2", context(listOf(MemoryReference(memory.id, memory.version))), 7)
        repository.answer("t2", 1, "安静的活动建议", emptyList(), 8)
        // Reproduce 0.4.1: a tombstone and deleted memory body, with derived chat copies retained.
        val event = MemoryEvent.Forgotten(memoryFingerprint(memory.text))
        db.recordings().insert(EventRow(eventId = "legacy-forgotten", aggregateType = "Memory", aggregateId = memory.id, aggregateVersion = memory.version + 1, eventType = "MemoryForgotten", payload = eventJson.encodeToString<MemoryEvent>(event), occurredAt = 9, recordedAt = 9, correlationId = "c", causationId = "legacy-forgotten", commandId = "legacy-forgotten"))
        db.processing().saveMemory(MemoryRow.from(evolveMemory(memory, event, null, null)))
        db.processing().deleteContent(memory.contentId)
        repository.purgeWithdrawnMemoryContent(10)
        assertNull(db.processing().content(requireNotNull(running.contextContentId)))
        assertNull(db.processing().content("t2:reply:1"))
        assertEquals("CONTEXT_WITHDRAWN", repository.turn("t2")?.failure)
        val eventCount = db.recordings().events().size
        repository.purgeWithdrawnMemoryContent(11)
        assertEquals(eventCount, db.recordings().events().size)
        RecordingRepository(db).rebuild()
        assertEquals("", repository.turn("t2")?.reply)
        assertEquals("喜欢安静", repository.turn("t1")?.userText)
    }
}
