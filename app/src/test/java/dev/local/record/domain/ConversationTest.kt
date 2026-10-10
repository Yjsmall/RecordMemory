package dev.local.record.domain

import dev.local.record.ai.parseAssistantReply
import dev.local.record.ai.selectAssistantMemories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test
    fun cancelledAndOldAttemptsCannotBecomeAnswers() {
        val requested = evolveTurn(AssistantTurn("t"), TurnEvent.Requested("c", 1, "u"), "hello")
        val running = evolveTurn(requested, TurnEvent.Started(1, "context"))
        val cancelled = evolveTurn(running, TurnEvent.Cancelled)
        assertTrue(runCatching { validateTurn(cancelled, TurnEvent.Answered(1, "reply")) }.isFailure)
        val retry = evolveTurn(cancelled, TurnEvent.Started(2, "context-2"))
        assertTrue(runCatching { validateTurn(retry, TurnEvent.Answered(1, "reply")) }.isFailure)
        validateTurn(retry, TurnEvent.Answered(2, "reply-2"))
    }

    @Test
    fun extractionRequiresEvidenceFromCurrentUserMessage() {
        val source = """{"reply":"我们可以按你的偏好安排","items":[{"type":"preference","text":"喜欢安静的环境","evidence":"我喜欢安静"},{"type":"person","text":"用户是医生","evidence":"我是一名医生"}]}"""
        val result = parseAssistantReply(source, "我喜欢安静，想聊聊工作")
        assertEquals("我们可以按你的偏好安排", result.text)
        assertEquals(1, result.memories.size)
        assertEquals(MemoryKind.PREFERENCE, result.memories.single().type)
    }

    @Test
    fun plainTextCompatibilityNeverCreatesInferredMemories() {
        val result = parseAssistantReply("我们一起想想吧。", "你好")
        assertEquals("我们一起想想吧。", result.text)
        assertTrue(result.memories.isEmpty())
        assertTrue(runCatching { parseAssistantReply("{\"items\":[]}", "hello") }.isFailure)
    }

    @Test
    fun onlyConfirmedMemoryEntersBoundedPersonalContext() {
        val memories = listOf(
            MemoryItem("a", status = MemoryStatus.CONFIRMED, type = MemoryKind.PREFERENCE, text = "喜欢简洁的回答"),
            MemoryItem("b", status = MemoryStatus.CANDIDATE, text = "未确认的身份"),
            MemoryItem("c", status = MemoryStatus.FORGOTTEN, text = "忘记的事实")
        )
        val selected = selectAssistantMemories(memories, "怎么回答？")
        assertEquals(listOf("a"), selected.map { it.id })
        val bounded = selectAssistantMemories((1..100).map { MemoryItem("$it", status = MemoryStatus.CONFIRMED, text = "x".repeat(500)) }, "hello")
        assertTrue(bounded.size <= 24)
        assertTrue(bounded.sumOf { it.text.length + 60 } <= 6_000)
        assertFalse(bounded.isEmpty())
    }
}
