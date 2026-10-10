package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.TurnStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedSourceWithdrawalTest {
    @Test fun deletingRecordingWithoutMemoriesWithdrawsReplyAndReceipt() = runTest {
        val db = database()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            RecordingRepository(db).append("r", 0, "r:start", RecordingEvent.Requested(1, "UTC"), 1)
            processing.reviseTranscript("r", "合成秘密原文", 2)
            val transcriptId = requireNotNull(processing.text("r").transcriptContentId)
            answeredFrom(conversations, "t", listOf(transcriptId))
            assertTrue(processing.memories().isEmpty())
            processing.onRecordingDeleted("r", 10)
            assertWithdrawn(db, "t")
            RecordingRepository(db).rebuild()
            assertWithdrawn(db, "t")
        } finally {
            db.close()
        }
    }

    @Test fun revisingTranscriptWithdrawsMultipleHopsAcrossConversationReplies() = runTest {
        val db = database()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            RecordingRepository(db).append("r", 0, "r:start", RecordingEvent.Requested(1, "UTC"), 1)
            processing.reviseTranscript("r", "合成秘密原文", 2)
            val transcriptId = requireNotNull(processing.text("r").transcriptContentId)
            answeredFrom(conversations, "t1", listOf(transcriptId))
            answeredFrom(conversations, "t2", listOf("t1:reply:1"))
            answeredFrom(conversations, "t3", listOf("t2:reply:1"))
            processing.reviseTranscript("r", "新的可用原文", 10)
            listOf("t1", "t2", "t3").forEach { assertWithdrawn(db, it) }
            RecordingRepository(db).rebuild()
            listOf("t1", "t2", "t3").forEach { assertWithdrawn(db, it) }
            assertTrue(HistorySearchRepository(db).search(HistoryQuery("合成秘密")).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun deletingConversationWithdrawsToolReadersOfBothUserAndReplyBodies() = runTest {
        val db = database()
        try {
            val conversations = ConversationRepository(db)
            answeredFrom(conversations, "origin", emptyList())
            answeredFrom(conversations, "userReader", listOf("origin:user"))
            answeredFrom(conversations, "replyReader", listOf("origin:reply:1"))
            answeredFrom(conversations, "downstream", listOf("replyReader:reply:1"))
            conversations.delete("c-origin", 10)
            listOf("userReader", "replyReader", "downstream").forEach { assertWithdrawn(db, it) }
            RecordingRepository(db).rebuild()
            listOf("userReader", "replyReader", "downstream").forEach { assertWithdrawn(db, it) }
            assertNull(HistorySearchRepository(db).read("origin:user"))
        } finally {
            db.close()
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()

    private suspend fun answeredFrom(conversations: ConversationRepository, id: String, sourceIds: List<String>) {
        conversations.create("c-$id", 3)
        conversations.request(id, "c-$id", "请查询原文", 4)
        conversations.start(id, AssistantContext("p", "服务", "m", "protocol", "", emptyList(), emptyList()), 5)
        if (sourceIds.isNotEmpty()) assertTrue(conversations.completeTool(id, 1, "source", "合成秘密回执", emptyList(), sourceIds, 6))
        assertTrue(conversations.answer(id, 1, "合成秘密回复", emptyList(), 7))
    }

    private suspend fun assertWithdrawn(db: RecordDatabase, id: String) {
        assertEquals(TurnStatus.FAILED, ConversationRepository(db).turn(id)?.status)
        assertNull(db.processing().content("$id:reply:1"))
        assertNull(db.processing().content("$id:tool:1:source"))
        assertNull(HistorySearchRepository(db).read("$id:reply:1"))
    }
}
