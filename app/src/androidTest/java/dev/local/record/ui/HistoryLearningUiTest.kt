package dev.local.record.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.LearningFeedback
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.AppAppearance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
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
        compose.onNodeWithTag("history-from").assertDoesNotExist()
        compose.onNodeWithTag("history-filters").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        compose.onNodeWithTag("history-filters").performClick()
        compose.onNodeWithTag("history-filters").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        compose.onNodeWithTag("history-from").performTextReplacement("2026-10-01")
        compose.onNodeWithTag("history-through").performTextReplacement("2026-10-10")
        compose.onNodeWithTag("history-project").performTextReplacement("随声记")
        compose.onNodeWithTag("history-filters").performScrollTo().performClick()
        compose.onNodeWithTag("history-from").assertDoesNotExist()
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
        compose.onNodeWithTag("plan-history").assertIsDisplayed()
        compose.onNodeWithTag("plan-history").performClick()
        assertEquals(0, paid)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, paid)
        compose.onNodeWithTag("plan-history").performClick()
        compose.onNodeWithTag("authorize-history").performClick()
        assertEquals(1, paid)
        assertEquals(setOf("t"), current.value.selectedHistory)
    }

    @Test fun windowChangesAndRestorationKeepFiltersSelectionAndLearningFeedbackReachable() {
        val hit = HistoryHit("u", "t", "USER_MESSAGE", "这周想把随声记的界面再打磨一下，让录音和回顾更顺手。", 1_791_552_000_000, "Asia/Shanghai")
        val current = mutableStateOf(AssistantUiState(ready = true, memoryConfigured = true, memoryProvider = "测试记忆服务", historySearched = true, historyHits = listOf(hit), selectedHistory = setOf("t"), allTurns = listOf(AssistantTurn("t", status = TurnStatus.ANSWERED)), learningFeedback = listOf(LearningFeedback("change", hit.observedAt, "替代旧记忆", "待审核", "本周优先打磨录音界面", "本周优先完成检索", source = "聊天原文", evidence = "这周想把随声记的界面再打磨一下"))))
        val dimensions = mutableStateOf(DpSize(380.dp, 800.dp))
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(dimensions.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale.value)) {
                    RecordTheme(appearance = if (dark.value) AppAppearance.DARK else AppAppearance.LIGHT) {
                        Surface(Modifier.fillMaxSize()) {
                            HistoryLearningScreen(current.value, {}, { current.value = current.value.copy(historyQuery = it) }, {}, { _, _ -> }, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        for ((name, size, font, night) in listOf(
            Layout("history-phone", DpSize(380.dp, 800.dp), 1f, false),
            Layout("history-fold", DpSize(900.dp, 700.dp), 1f, false),
            Layout("history-large-font", DpSize(360.dp, 650.dp), 1.5f, false),
            Layout("history-compact-dark", DpSize(360.dp, 320.dp), 1f, true)
        )) {
            compose.runOnIdle {
                dimensions.value = size
                scale.value = font
                dark.value = night
            }
            compose.onNodeWithTag("plan-history").assertIsDisplayed()
            screenshot(name)
        }
        compose.runOnIdle {
            dimensions.value = DpSize(380.dp, 800.dp)
            scale.value = 1f
            dark.value = false
        }
        compose.onNodeWithTag("history-filters").performScrollTo().performClick()
        compose.onNodeWithTag("history-from").performTextReplacement("2026-10-01")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-from").performScrollTo().assertTextContains("2026-10-01")
        compose.onNodeWithTag("plan-history").assertIsDisplayed()
        compose.onNodeWithTag("learning-tab").performClick()
        compose.onNodeWithText("待审核").assertIsDisplayed()
        compose.onNodeWithText("建议改为").assertIsDisplayed()
        screenshot("learning-phone")
        compose.onNodeWithText("来源与依据").performScrollTo().performClick()
        compose.onNodeWithText("依据：这周想把随声记的界面再打磨一下").performScrollTo().assertIsDisplayed()
    }

    private data class Layout(val name: String, val size: DpSize, val font: Float, val night: Boolean)

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
