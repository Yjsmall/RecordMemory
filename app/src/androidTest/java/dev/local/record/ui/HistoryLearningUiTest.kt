package dev.local.record.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryLearningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingFiltersUsesLatestQueryAndNeverStartsPaidWork() {
        val current = mutableStateOf(AssistantUiState(ready = true))
        var searched: HistoryQuery? = null
        var paid = 0
        compose.setContent {
            RecordTheme {
                HistoryLearningScreen(current.value, {}, { current.value = current.value.copy(historyQuery = it) }, { searched = current.value.historyQuery }, { _, _ -> }, { paid++ }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("history-keyword").performTextReplacement("原型")
        compose.onNodeWithTag("history-from").performTextReplacement("2026-10-01")
        compose.onNodeWithTag("history-through").performTextReplacement("2026-10-10")
        compose.onNodeWithTag("history-project").performTextReplacement("随声记")
        compose.onNodeWithTag("search-history").performScrollTo().performClick()
        assertEquals(HistoryQuery("原型", "2026-10-01", "2026-10-10", "随声记"), searched)
        assertEquals(0, paid)
    }

    @Test fun selectingHistoryRequiresSeparateUploadAuthorizationAndCancelDoesNotQueue() {
        val user = HistoryHit("u", "t", "USER_MESSAGE", "我喜欢咖啡", 1, "UTC")
        val machine = HistoryHit("asr", "r", "MACHINE_TRANSCRIPT", "机器识别内容", 1, "UTC")
        val current = mutableStateOf(AssistantUiState(ready = true, memoryConfigured = true, memoryProvider = "synthetic", historySearched = true, historyHits = listOf(user, machine), allTurns = listOf(AssistantTurn("t", status = TurnStatus.ANSWERED))))
        var paid = 0
        compose.setContent {
            RecordTheme {
                HistoryLearningScreen(current.value, {}, {}, {}, { hit, selected -> current.value = current.value.copy(selectedHistory = if (selected) setOf(hit.ownerId) else emptySet()) }, { paid++ }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("select-history-r").assertDoesNotExist()
        compose.onNodeWithTag("select-history-t").performScrollTo().performClick()
        compose.onNodeWithTag("history-results").performScrollToNode(hasTestTag("plan-history"))
        compose.onNodeWithTag("plan-history").performClick()
        assertEquals(0, paid)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, paid)
        compose.onNodeWithTag("plan-history").performClick()
        compose.onNodeWithTag("authorize-history").performClick()
        assertEquals(1, paid)
        assertEquals(setOf("t"), current.value.selectedHistory)
    }
}
