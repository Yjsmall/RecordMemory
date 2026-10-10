package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryEvent
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemorySource
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.evolveMemory
import dev.local.record.domain.memoryFingerprint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySourceValidityTest {
    @Test fun activeCoffeeSuppressionMasksNewOrdinaryMentionUntilExplicitRelearning() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            val text = "我喜欢合成咖啡"
            conversations.create("c", 1)
            conversations.request("t", "c", text, 2)
            val source = MemorySource("t:user", conversationId = "c", turnId = "t", evidence = text, start = 0, end = text.length, observedAt = 2, zoneId = "UTC")
            processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PREFERENCE, text, text, MemoryFact("self", "drink.coffee", sources = listOf(source)))), 3, explicit = true)
            val memory = processing.memories().single()
            processing.forgetMemory(memory.id, 4)
            conversations.request("new", "c", "今天普通聊天提到了拿铁", 5)
            conversations.start("new", AssistantContext("p", "服务", "m", "protocol", "", emptyList(), emptyList()), 5)
            conversations.answer("new", 1, "收到", emptyList(), 5)
            assertNull(conversations.sourceText("new:user"))
            assertNull(HistorySearchRepository(db).read("new:reply:1"))
            assertTrue(HistorySearchRepository(db).search(dev.local.record.domain.HistoryQuery("拿铁")).isEmpty())
            processing.allowMemoryRelearning(memory.id, 6)
            assertTrue(conversations.sourceText("new:user") != null)
            assertNull(conversations.sourceText("t:user"))
        } finally {
            db.close()
        }
    }

    @Test fun forgettingMasksEveryLinkedChatAndRecordingSourceEvenAfterRelearningAndReplay() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            val text = "我喜欢合成咖啡"
            RecordingRepository(db).append("r", 0, "r:start", RecordingEvent.Requested(1, "UTC"), 1)
            processing.reviseTranscript("r", text, 2)
            val transcriptId = requireNotNull(processing.text("r").transcriptContentId)
            conversations.create("c", 3)
            conversations.request("t", "c", text, 4)
            val sources = listOf(
                MemorySource("t:user", conversationId = "c", turnId = "t", evidence = text, start = 0, end = text.length, observedAt = 4, zoneId = "UTC"),
                MemorySource(transcriptId, recordingId = "r", origin = "TRANSCRIPT", evidence = text, start = 0, end = text.length, observedAt = 1, zoneId = "UTC")
            )
            processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PREFERENCE, text, text, MemoryFact("self", "drink.coffee", sources = sources))), 5, explicit = true)
            val memory = processing.memories().single()
            processing.forgetMemory(memory.id, 6)
            assertNull(conversations.sourceText("t:user"))
            assertNull(conversations.sourceText(transcriptId))
            processing.allowMemoryRelearning(memory.id, 7)
            RecordingRepository(db).rebuild()
            assertNull(conversations.sourceText("t:user"))
            assertNull(conversations.sourceText(transcriptId))
            assertNull(db.processing().content(memory.contentId))
        } finally {
            db.close()
        }
    }

    @Test fun revisedTranscriptInvalidatesAlreadyAcceptedToolContextBeforeAnswer() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            RecordingRepository(db).append("r", 0, "r:start", RecordingEvent.Requested(1, "UTC"), 1)
            processing.reviseTranscript("r", "旧的合成项目内容", 2)
            val oldId = requireNotNull(processing.text("r").transcriptContentId)
            conversations.create("c", 3)
            conversations.request("t", "c", "搜索项目", 4)
            conversations.start("t", AssistantContext("p", "服务", "m", "protocol", "", emptyList(), emptyList()), 5)
            assertTrue(conversations.completeTool("t", 1, "old", "旧片段", emptyList(), listOf(oldId), 6))
            processing.reviseTranscript("r", "新的合成项目内容", 7)
            assertFalse(conversations.answer("t", 1, "引用旧转写的迟到答案", emptyList(), 8))
        } finally {
            db.close()
        }
    }

    @Test fun revisedTranscriptCannotBeReadOrAcceptedInToolReceipt() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val processing = ProcessingRepository(db)
            val conversations = ConversationRepository(db)
            RecordingRepository(db).append("r", 0, "r:start", RecordingEvent.Requested(1, "UTC"), 1)
            processing.reviseTranscript("r", "旧的合成项目内容", 2)
            val oldId = requireNotNull(processing.text("r").transcriptContentId)
            conversations.create("c", 3)
            conversations.request("t", "c", "搜索项目", 4)
            conversations.start("t", AssistantContext("p", "服务", "m", "protocol", "", emptyList(), emptyList()), 5)
            processing.reviseTranscript("r", "新的合成项目内容", 6)
            assertNull(conversations.sourceText(oldId))
            assertFalse(conversations.completeTool("t", 1, "old", "旧片段", emptyList(), listOf(oldId), 7))
        } finally {
            db.close()
        }
    }

    @Test fun rejectedLegacyFactMasksRetainedUserSource() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        try {
            val conversations = ConversationRepository(db)
            val processing = ProcessingRepository(db)
            conversations.create("c", 1)
            conversations.request("t", "c", "我喜欢合成咖啡，其他原文也应屏蔽", 2)
            processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢合成咖啡", "我喜欢合成咖啡")), 3)
            val memory = processing.memories().single()
            // Legacy tombstone lacked sourceContentIds and had already erased its private fact body.
            val event = MemoryEvent.Invalidated(memoryFingerprint(memory.text))
            db.recordings().insert(EventRow(eventId = "legacy", aggregateType = "Memory", aggregateId = memory.id, aggregateVersion = memory.version + 1, eventType = "MemoryInvalidated", payload = eventJson.encodeToString<MemoryEvent>(event), occurredAt = 4, recordedAt = 4, correlationId = "c", causationId = "legacy", commandId = "legacy"))
            db.processing().saveMemory(MemoryRow.from(evolveMemory(memory, event, null, null)))
            db.processing().deleteContent(memory.contentId)
            assertNull(conversations.sourceText("t:user"))
            RecordingRepository(db).rebuild()
            assertNull(conversations.sourceText("t:user"))
        } finally {
            db.close()
        }
    }
}
