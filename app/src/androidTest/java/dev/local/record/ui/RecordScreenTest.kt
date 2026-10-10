package dev.local.record.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class RecordScreenTest {
    @get:Rule val compose = createComposeRule()
    private val sample = Recording("sample", 3, 1_760_000_000_000, "Asia/Shanghai", RecordingStatus.SAVED, "sample.m4a", 15_000)

    @Test
    fun leftSwipeRevealsDeleteAndRequiresConfirmation() {
        val recordings = mutableStateOf(listOf(sample))
        var deletions = 0
        compose.setContent {
            RecordTheme {
                RecordScreen(recordings.value, SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}, onDelete = {
                    deletions++
                    recordings.value = emptyList()
                })
            }
        }
        compose.onNodeWithTag("recording-sample").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithTag("delete-recording-sample").assertExists()
        assertEquals(0, deletions)
        compose.onNodeWithTag("recording-sample").performTouchInput { swipeRight() }
        compose.onNodeWithTag("delete-recording-sample").assertDoesNotExist()
        compose.onNodeWithTag("recording-sample").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("delete-recording-sample").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, deletions)
        compose.onNodeWithTag("delete-recording-sample").performClick()
        compose.onNodeWithTag("confirm-delete-recording").performClick()
        assertEquals(1, deletions)
        compose.onNodeWithTag("recording-sample").assertDoesNotExist()
    }

    @Test
    fun activeRecordingCannotRevealDelete() {
        compose.setContent {
            RecordTheme {
                RecordScreen(listOf(sample.copy(status = RecordingStatus.RECORDING)), SessionState(SessionPhase.RECORDING, sample.id), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("recording-list").performScrollToNode(hasTestTag("recording-sample"))
        compose.onNodeWithTag("recording-sample").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("delete-recording-sample").assertDoesNotExist()
    }

    @Test
    fun navigationRestoresWithoutStartingRecording() {
        var starts = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecordTheme {
                RecordScreen(listOf(sample), SessionState(), PlaybackState(), true, null, true, { starts++ }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("recording-sample").performScrollTo().performClick()
        compose.onNodeWithTag("recording-detail").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recording-detail").assertExists()
        assertEquals(0, starts)
    }

    @Test
    fun windowMatrixKeepsRecordingControlsReachableAndCapturesScreenshots() {
        val dimensions = mutableStateOf(DpSize(400.dp, 400.dp))
        val fontScale = mutableStateOf(1f)
        val active = SessionState(SessionPhase.RECORDING, "live", 6_000, 0.4f)
        var stops = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(dimensions.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale.value)) {
                    RecordTheme {
                        RecordScreen(listOf(sample), active, PlaybackState(), true, null, true, {}, {}, { stops++ }, {}, {}, {})
                    }
                }
            }
        }
        for (width in listOf(400, 610, 900)) {
            for (height in listOf(400, 500, 1000)) {
                compose.runOnIdle { dimensions.value = DpSize(width.dp, height.dp) }
                compose.onNodeWithText("停止并保存").performScrollTo().assertExists()
                screenshot("recording-${width}x$height")
            }
        }
        compose.runOnIdle {
            dimensions.value = DpSize(400.dp, 500.dp)
            fontScale.value = 1.5f
        }
        compose.onNodeWithText("停止并保存").performScrollTo().performClick()
        assertEquals(1, stops)
        screenshot("recording-large-font")
    }

    @Test
    fun interruptedAudioHasPlaybackAndClearProblem() {
        compose.setContent {
            RecordTheme {
                RecordScreen(
                    listOf(sample.copy(status = RecordingStatus.INTERRUPTED, problem = "上次录音意外中断")),
                    SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}
                )
            }
        }
        compose.onNodeWithTag("recording-sample").performScrollTo().performClick()
        compose.onNodeWithText("上次录音意外中断").assertExists()
        compose.onNodeWithText("播放录音").performScrollTo().assertExists()
    }

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
