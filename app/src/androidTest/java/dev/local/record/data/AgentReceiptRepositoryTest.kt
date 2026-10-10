package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReceiptRepositoryTest {
    @Test fun toolReceiptIsIdempotentReplayableAndDeletedWithForgottenEvidence() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val processing = ProcessingRepository(db)
            conversations.create("c", 1)
            conversations.request("source", "c", "我喜欢咖啡", 2)
            processing.proposeConversationMemories("source", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢咖啡", "我喜欢咖啡")), 3, explicit = true)
            conversations.cancel("source", 4)
            val memory = processing.memories().single()
            conversations.request("t", "c", "我的习惯？", 5)
            conversations.start("t", AssistantContext("p", "服务", "m", "protocol", "prompt", emptyList(), emptyList()), 6)
            val refs = listOf(MemoryReference(memory.id, memory.version))
            assertTrue(conversations.completeTool("t", 1, "call", "工具私密正文", refs, emptyList(), 7))
            assertTrue(conversations.completeTool("t", 1, "call", "工具私密正文", refs, emptyList(), 8))
            assertEquals(1, db.recordings().events().count { it.eventType == "AgentToolCallCompleted" })
            val before = conversations.turn("t")
            RecordingRepository(db).rebuild()
            assertEquals(before, conversations.turn("t"))
            processing.forgetMemory(memory.id, 9)
            assertFalse(conversations.answer("t", 1, "迟到结果", emptyList(), 10))
            assertNull(db.processing().content("t:tool:1:call"))
            RecordingRepository(db).rebuild()
            assertNull(db.processing().content("t:tool:1:call"))
        } finally {
            db.close()
        }
    }
}
