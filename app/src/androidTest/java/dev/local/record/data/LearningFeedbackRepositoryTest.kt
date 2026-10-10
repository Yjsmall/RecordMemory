package dev.local.record.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningFeedbackRepositoryTest {
    @Test fun recentViewTracksReviewAndForgetWithoutRetainingAuditCopies() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            conversations.create("c", 1)
            conversations.request("t", "c", "我喜欢咖啡", 2)
            conversations.start("t", AssistantContext("p", "synthetic", "m", "openai-compatible-v1", "", emptyList(), emptyList()), 3)
            conversations.answer("t", 1, "收到", emptyList(), 4)
            val processing = ProcessingRepository(db)
            processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PREFERENCE, "我喜欢咖啡", "我喜欢咖啡")), 5)
            val memory = processing.memories().single()
            val feedback = LearningFeedbackRepository(db)
            assertEquals("待审核", feedback.recent().single().status)
            processing.confirmMemory(memory.id, 6)
            assertEquals("已保存", feedback.recent().single().status)
            processing.forgetMemory(memory.id, 7)
            val after = feedback.recent().single()
            assertEquals("已忘记", after.status)
            assertEquals("", after.text)
            assertEquals("", after.evidence)
            RecordingRepository(db).rebuild()
            assertEquals(after, feedback.recent().single())
            assertFalse(db.recordings().events().any { it.payload.contains("我喜欢咖啡") })
        } finally {
            db.close()
        }
    }
}
