package dev.local.record.domain

import dev.local.record.ai.doubaoBody
import dev.local.record.ai.parseDoubaoTranscript
import dev.local.record.ai.parseMemoryItems
import dev.local.record.ai.parseOpenAiTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTest {
    @Test
    fun userTitleIsKeptAsSuggestion() {
        val edited = evolveText(RecordingText("r"), TextEvent.TitleSet("title-1", TextOrigin.USER.name), "人工标题")
        val event = titleEventFor(edited, "title-2")
        assertTrue(event is TextEvent.TitleSuggested)
        val suggested = evolveText(edited, event, "模型标题")
        assertEquals("人工标题", suggested.title)
        assertEquals("模型标题", suggested.titleSuggestion)
        assertEquals(
            suggested,
            listOf(TextEvent.TitleSet("title-1", "USER"), event).fold(RecordingText("r")) { state, item ->
                evolveText(state, item, if (item is TextEvent.TitleSet) "人工标题" else "模型标题")
            }
        )
    }

    @Test
    fun forgottenMemoryDoesNotReturn() {
        val proposed = evolveMemory(
            MemoryItem("m"),
            MemoryEvent.Proposed(MemoryKind.TODO.name, "c", "rec", "transcript"),
            "周五健身",
            "周五健身"
        )
        val forgotten = evolveMemory(proposed, MemoryEvent.Forgotten(memoryFingerprint("周五健身")), null, null)
        assertEquals(MemoryStatus.FORGOTTEN, forgotten.status)
        assertEquals("", forgotten.text)
        assertTrue(duplicatesMemory(listOf(forgotten), "rec", "周五 健身"))
        assertFalse(duplicatesMemory(listOf(forgotten), "other", "周五健身"))
    }

    @Test
    fun memoryJsonIgnoresUnsupportedTypesAndEmptyItems() {
        val items = parseMemoryItems(
            """```json
            {"items":[{"type":"person","text":"小王","evidence":"小王说明天"},{"type":"unknown","text":"忽略"},{"type":"todo","text":"  "}]}
            ```"""
        )
        assertEquals(listOf(ParsedExpectation("小王", MemoryKind.PERSON)), items.map { ParsedExpectation(it.text, it.type) })
    }

    @Test
    fun transcriptParsersRejectIncompletePayloads() {
        assertEquals("明天下午开会", parseOpenAiTranscript("""{"text":"明天下午开会"}"""))
        assertEquals("明天下午开会", parseDoubaoTranscript("""{"result":{"text":"明天下午开会"}}""", "20000000"))
        assertThrows(IllegalArgumentException::class.java) { parseDoubaoTranscript("""{"result":{"text":"秘密"}}""", "45000001") }
        assertTrue(doubaoBody("abc", "bigmodel", "zh").contains("\"format\":\"wav\""))
        assertFalse(doubaoBody("abc", "bigmodel", "zh").contains("api-key"))
    }

    private data class ParsedExpectation(val text: String, val type: MemoryKind)
}
