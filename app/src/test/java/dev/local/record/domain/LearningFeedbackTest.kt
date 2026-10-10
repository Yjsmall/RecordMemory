package dev.local.record.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningFeedbackTest {
    @Test fun forgottenContentCannotReappearEvenIfCallerSuppliesOldText() {
        val memory = MemoryItem("m", status = MemoryStatus.FORGOTTEN, text = "private old text", evidence = "old evidence")
        val feedback = memoryFeedback(memory, "MemoryForgotten", 10, null)
        assertEquals("已忘记", feedback.status)
        assertEquals("", feedback.text)
        assertEquals("", feedback.previousText)
        assertFalse(feedback.source.contains("old evidence"))
    }

    @Test fun replacementShowsBothRetainedVersionsAndCurrentReviewState() {
        val old = MemoryItem("old", status = MemoryStatus.SUPERSEDED, text = "我喜欢咖啡")
        val next = MemoryItem("next", status = MemoryStatus.CANDIDATE, text = "我不喝咖啡", sourceTurnId = "t", change = MemoryChange(MemoryAction.SUPERSEDE, old.id, 2))
        val feedback = memoryFeedback(next, "ConversationMemoryProposed", 20, old)
        assertEquals("替代旧记忆", feedback.label)
        assertEquals("待审核", feedback.status)
        assertEquals(old.text, feedback.previousText)
        assertEquals("聊天原文", feedback.source)
    }

    @Test fun changedOrDeletedOldMemoryNeverLeaksIntoReplacement() {
        val old = MemoryItem("old", status = MemoryStatus.INVALIDATED, text = "stale secret")
        val next = MemoryItem("next", text = "new", change = MemoryChange(MemoryAction.SUPERSEDE, old.id, 1))
        assertEquals("", memoryFeedback(next, "ConversationMemoryProposed", 20, old).previousText)
    }
}
