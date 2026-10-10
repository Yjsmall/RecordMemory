package dev.local.record.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MemoryKnowledgeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun conflictShowsOldFactAndCorrectsThatFactWithoutConfirmingQuestion() {
        val old = MemoryItem("old", version = 2, type = MemoryKind.PREFERENCE, status = MemoryStatus.CONFIRMED, text = "我喜欢咖啡", fact = MemoryFact("self", "drink.coffee"))
        val candidate = MemoryItem("candidate", text = "我不喝咖啡", fact = old.fact, change = MemoryChange(MemoryAction.ASK_USER, old.id, old.version, "这是长期变化还是今天的安排？"))
        var correctedId = ""
        var correctedText = ""
        compose.setContent {
            RecordTheme {
                MemoryLibrary(listOf(candidate, old), {}, {}, {}, { id, value ->
                    correctedId = id
                    correctedText = value
                }, { _, _ -> })
            }
        }
        compose.onNodeWithText("旧记忆：我喜欢咖啡").assertExists()
        compose.onNodeWithText("这是长期变化还是今天的安排？").assertExists()
        compose.onNodeWithTag("confirm-memory-candidate").assertDoesNotExist()
        compose.onNodeWithText("纠正旧记忆").performClick()
        compose.onNodeWithTag("content-editor").performTextReplacement("我偶尔喝咖啡")
        compose.onNodeWithText("保存").performClick()
        assertEquals("old", correctedId)
        assertEquals("我偶尔喝咖啡", correctedText)
    }

    @Test fun relearningRequiresExplicitScopeReviewAndNeverShowsDeletedText() {
        val tombstone = MemoryItem("forgotten", type = MemoryKind.PREFERENCE, status = MemoryStatus.FORGOTTEN, suppressionKey = "hashed-scope", suppressionLabel = "我 · 咖啡习惯")
        var allowed = ""
        compose.setContent {
            RecordTheme { MemoryLibrary(listOf(tombstone), {}, {}, {}, { _, _ -> }, { _, _ -> }, onAllowRelearning = { allowed = it }) }
        }
        compose.onNodeWithTag("memory-forgotten").assertDoesNotExist()
        compose.onNodeWithText("防重新学习范围 1").performClick()
        compose.onNodeWithText("我 · 咖啡习惯").assertExists()
        compose.onNodeWithTag("allow-relearning-forgotten").performClick()
        assertEquals("", allowed)
        compose.onNodeWithText("取消").performClick()
        assertEquals("", allowed)
        compose.onNodeWithTag("allow-relearning-forgotten").performClick()
        compose.onNodeWithText("允许").performClick()
        assertEquals("forgotten", allowed)
    }
}
