package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.ai.AgentToolCall
import dev.local.record.ai.AgentTools
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemorySource
import dev.local.record.domain.RecordingEvent
import dev.local.record.settings.AssistantPreferences
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HistorySearchRepositoryTest {
    private lateinit var db: RecordDatabase
    private lateinit var history: HistorySearchRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var processing: ProcessingRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecordDatabase::class.java).build()
        history = HistorySearchRepository(db)
        conversations = ConversationRepository(db)
        processing = ProcessingRepository(db)
    }

    @After fun close() = db.close()

    private suspend fun transcript(id: String, text: String, at: Long, zone: String = "UTC") {
        RecordingRepository(db).append(id, 0, "$id:start", RecordingEvent.Requested(at, zone), at)
        processing.reviseTranscript(id, text, at + 1)
    }

    @Test fun datesAreInclusiveInOriginalSourceZoneAndKeywordsUseChineseBigrams() = runTest {
        transcript("included", "这是合成项目的开发笔记", Instant.parse("2026-10-09T16:00:00Z").toEpochMilli(), "Asia/Shanghai")
        transcript("before", "合成项目开发笔记", Instant.parse("2026-10-09T15:59:59Z").toEpochMilli(), "Asia/Shanghai")
        transcript("after", "合成项目开发笔记", Instant.parse("2026-10-10T16:00:00Z").toEpochMilli(), "Asia/Shanghai")
        val results = history.search(HistoryQuery("项目进度", "2026-10-10", "2026-10-10"))
        assertEquals(listOf("included"), results.map { it.ownerId })
        assertEquals("USER_TRANSCRIPT", results.single().origin)
        assertEquals("Asia/Shanghai", results.single().zoneId)
        assertTrue(history.search(HistoryQuery("无关词汇")).isEmpty())
    }

    @Test fun projectMatchesLiteralTextOrExplicitLinkedScopeAndChatRepliesAreSearchable() = runTest {
        transcript("literal", "合成星图正在测试", 1)
        transcript("unrelated", "泛泛谈起其他项目", 2)
        conversations.create("c", 3)
        conversations.request("t", "c", "我在调试页面", 4)
        conversations.start("t", AssistantContext("p", "服务", "m", "protocol", "", emptyList(), emptyList()), 5)
        conversations.answer("t", 1, "合成项目的助手回复", emptyList(), 6)
        val source = MemorySource("t:user", conversationId = "c", turnId = "t", evidence = "我在调试页面", start = 0, end = 6, observedAt = 4, zoneId = "UTC")
        processing.proposeConversationMemories("t", listOf(MemoryDraft(MemoryKind.PROJECT, "正在调试", "我在调试页面", MemoryFact("self", "project.status", "合成星图", sources = listOf(source)))), 7, explicit = true)
        assertEquals(setOf("literal", "t"), history.search(HistoryQuery(project = "合成星图")).map { it.ownerId }.toSet())
        assertEquals("ASSISTANT_REPLY", history.search(HistoryQuery("助手回复")).single().origin)
        assertTrue(history.search(HistoryQuery(project = "星图的别名")).isEmpty())
    }

    @Test fun resultsAreBoundedSnippetsButOpeningSourceReturnsFullCurrentBody() = runTest {
        repeat(12) { transcript("r$it", "起始".repeat(200) + "独有检索词" + "尾部".repeat(200), it.toLong()) }
        val hits = history.search(HistoryQuery("独有检索词"))
        assertEquals(10, hits.size)
        assertTrue(hits.all { it.text.length <= 300 && it.text.contains("独有检索词") })
        assertTrue(requireNotNull(history.read(hits.first().contentId)).text.length > 800)
        val old = hits.first()
        processing.reviseTranscript(old.ownerId, "修订后的新正文", 20)
        assertNull(history.read(old.contentId))
    }

    @Test fun toolsSearchUnassociatedHistoryAndTrackImmutableDependencies() = runTest {
        transcript("r", "这是无记忆卡关联的合成记录", 1)
        val tools = AgentTools(processing, conversations, null, null, { AssistantPreferences() }, mutableSetOf())
        val result = tools.execute(AgentToolCall("call", "search_local_sources", buildJsonObject { put("query", "合成记录") }))
        assertTrue(result.output.contains("无记忆卡关联"))
        assertEquals(listOf(requireNotNull(processing.text("r").transcriptContentId)), result.sources)
        assertTrue(result.memories.isEmpty())
        val dateOnly = tools.execute(
            AgentToolCall(
                "date",
                "search_local_sources",
                buildJsonObject {
                    put("fromDate", "1970-01-01")
                    put("throughDate", "1970-01-01")
                }
            )
        )
        assertEquals(result.sources, dateOnly.sources)
        val read = tools.execute(AgentToolCall("read", "read_local_source", buildJsonObject { put("id", result.sources.single()) }))
        assertTrue(read.output.contains("合成记录"))
        processing.reviseTranscript("r", "新版", 3)
        assertFalse(tools.execute(AgentToolCall("late", "read_local_source", buildJsonObject { put("id", result.sources.single()) })).output.contains("合成记录"))
    }
}
