package dev.local.record.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AssistantScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(AssistantUiState(ready = true, configured = true, conversationId = "c", provider = "测试服务 · assistant-model"))
    private val dimensions = mutableStateOf(DpSize(380.dp, 800.dp))
    private val font = mutableStateOf(1f)
    private val dark = mutableStateOf(false)
    private val session = mutableStateOf(SessionState())
    private var sent = 0
    private var configured = 0
    private var stopped = 0
    private var confirmed = ""
    private var ignored = ""
    private var retried = ""
    private var cancelled = ""
    private var remembered = ""

    private fun content() = compose.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(dimensions.value)) {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font.value)) {
                RecordTheme(appearance = if (dark.value) dev.local.record.settings.AppAppearance.DARK else dev.local.record.settings.AppAppearance.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        AssistantScreen(state.value, session.value, {}, { state.value = state.value.copy(draft = it) }, { sent++ }, { retried = it }, { cancelled = it }, {}, {}, {}, { _, text, _ -> remembered = text }, { confirmed = it }, { ignored = it }, {}, { configured++ }, {}, { stopped++ })
                    }
                }
            }
        }
    }

    private fun turn(status: TurnStatus = TurnStatus.ANSWERED) = AssistantTurn("t", version = 3, conversationId = "c", sequence = 1, status = status, userText = "我更喜欢安静一点的地方，也希望周末留一点时间给自己。", reply = "那我们可以把周末安排得松一点。先留出半天不排活动，再选一个安静的地方走走。")

    @Test fun inputAndConfigurationGateSending() {
        state.value = state.value.copy(configured = false)
        content()
        compose.onNodeWithTag("configure-assistant").performClick()
        assertEquals(1, configured)
        compose.onNodeWithTag("assistant-input").performTextReplacement("你好")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(configured = true) }
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
        assertEquals(1, sent)
        compose.runOnIdle { state.value = state.value.copy(allTurns = listOf(turn(TurnStatus.RUNNING))) }
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.onNodeWithText("停止").performScrollTo().performClick()
        assertEquals("t", cancelled)
        compose.runOnIdle { state.value = state.value.copy(allTurns = listOf(turn(TurnStatus.FAILED))) }
        compose.onNodeWithText("重试").performScrollTo().performClick()
        assertEquals("t", retried)
    }

    @Test fun candidateConfirmationAndManualMemoryAreExplicitActions() {
        state.value = state.value.copy(allTurns = listOf(turn()), memories = listOf(MemoryItem(id = "m", type = MemoryKind.PREFERENCE, status = MemoryStatus.CANDIDATE, text = "喜欢安静的地方", sourceConversationId = "c", sourceTurnId = "t")))
        content()
        compose.onNodeWithTag("assistant-confirm-m").performScrollTo().performClick()
        assertEquals("m", confirmed)
        compose.onNodeWithText("忽略").performScrollTo().performClick()
        assertEquals("m", ignored)
        compose.onNodeWithTag("remember-turn-t").performScrollTo().performClick()
        compose.onNodeWithTag("remember-input").performTextReplacement("周末留出半天休息")
        compose.onNodeWithText("保存记忆").performClick()
        assertEquals("周末留出半天休息", remembered)
    }

    @Test fun narrowWideLargeFontAndCompactWindowsKeepComposerAndRecordingActions() {
        state.value = state.value.copy(allTurns = listOf(turn()), draft = "帮我安排一个轻松的周末", memories = listOf(MemoryItem(id = "m", type = MemoryKind.PREFERENCE, status = MemoryStatus.CANDIDATE, text = "喜欢安静的地方", sourceConversationId = "c", sourceTurnId = "t")))
        session.value = SessionState(SessionPhase.RECORDING, "live", 12_000)
        content()
        for ((name, size, scale, night) in listOf(
            Layout("assistant-phone", DpSize(380.dp, 800.dp), 1f, false),
            Layout("assistant-fold", DpSize(900.dp, 700.dp), 1f, false),
            Layout("assistant-large-font", DpSize(360.dp, 650.dp), 1.5f, false),
            Layout("assistant-compact-dark", DpSize(360.dp, 320.dp), 1f, true)
        )) {
            compose.runOnIdle {
                dimensions.value = size
                font.value = scale
                dark.value = night
            }
            compose.onNodeWithTag("assistant-input").assertIsDisplayed().assertTextContains("帮我安排", substring = true)
            compose.onNodeWithTag("assistant-send").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("停止保存").assertIsDisplayed()
            screenshot(name)
        }
        compose.onNodeWithText("停止保存").performClick()
        assertEquals(1, stopped)
    }

    private data class Layout(val name: String, val size: DpSize, val scale: Float, val night: Boolean)

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
