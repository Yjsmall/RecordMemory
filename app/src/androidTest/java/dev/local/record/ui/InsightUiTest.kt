package dev.local.record.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.local.record.audio.SessionState
import dev.local.record.domain.AiJob
import dev.local.record.domain.JobStatus
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingStatus
import dev.local.record.domain.RecordingText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class InsightUiTest {
    @get:Rule val compose = createComposeRule()
    private val recording = Recording("sample", 3, 1_760_000_000_000, "Asia/Shanghai", RecordingStatus.SAVED, "sample.m4a", 65_000)
    private val text = RecordingText("sample", title = "把想法留到下次见面", transcript = "今天讨论了下周的发布计划。周五之前完成录音功能验收，再一起整理反馈。", summary = "下周发布前，先完成录音验收与反馈整理。\n\n• 周五前检查暂停、保存和播放\n• 汇总手机上的使用反馈")
    private val memory = MemoryItem("memory", type = MemoryKind.TODO, text = "周五前完成录音功能验收", evidence = "周五之前完成录音功能验收", sourceRecordingId = "sample")

    @Test
    fun sectionsKeepContentReadableAndEditorRequiresExplicitDiscard() {
        var saved = ""
        compose.setContent {
            RecordTheme {
                RecordScreen(listOf(recording), SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}, insights = listOf(text), memories = listOf(memory), onSaveTranscript = { _, value -> saved = value })
            }
        }
        compose.onNodeWithTag("recording-sample").performScrollTo().performClick()
        compose.onNodeWithTag("insight-summary").performScrollTo()
        screenshot("insight-summary-phone")
        compose.onNodeWithTag("insight-tab-1").performClick()
        compose.onNodeWithTag("transcript-editor").performScrollTo().performClick()
        compose.onNodeWithTag("content-editor").performTextReplacement("校正后的原文")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("校正后的原文", saved)
        compose.onNodeWithTag("insight-transcript").performScrollTo()
        screenshot("insight-transcript-phone")
        compose.onNodeWithTag("insight-tab-2").performClick()
        compose.onNodeWithTag("memory-memory").performScrollTo()
        screenshot("insight-memory-phone")
    }

    @Test
    fun queuedTranscriptionDisablesDuplicateSubmission() {
        compose.setContent {
            RecordTheme {
                RecordScreen(listOf(recording), SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}, jobs = listOf(AiJob("job", recordingId = "sample", capability = "ASR", status = JobStatus.REQUESTED, generation = 2)))
            }
        }
        compose.onNodeWithTag("recording-sample").performScrollTo().performClick()
        compose.onNodeWithTag("insight-tab-1").performClick()
        compose.onNodeWithTag("generate-transcript").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("等待处理").assertExists()
    }

    @Test
    fun memoryMenuRequiresConfirmationAndSourceOpensRecording() {
        var forgotten = 0
        compose.setContent {
            RecordTheme {
                RecordScreen(listOf(recording), SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}, memories = listOf(memory), onForgetMemory = { forgotten++ })
            }
        }
        compose.onNodeWithTag("open-memories").performClick()
        compose.onNodeWithTag("memory-menu-memory").performClick()
        compose.onNodeWithText("忘记").performClick()
        assertEquals(0, forgotten)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, forgotten)
        compose.onNodeWithTag("memory-menu-memory").performClick()
        compose.onNodeWithText("忘记").performClick()
        compose.onNodeWithText("确认").performClick()
        assertEquals(1, forgotten)
        compose.onNodeWithText("原录音").performClick()
        compose.onNodeWithTag("recording-detail").assertExists()
    }

    @Test
    fun memoryGridAdaptsToWidthAndLargeTypeWithReachableControls() {
        val size = mutableStateOf(DpSize(360.dp, 700.dp))
        val scale = mutableStateOf(1f)
        var confirmed = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale.value)) {
                    RecordTheme {
                        MemoryLibrary(listOf(memory, memory.copy(id = "other", type = MemoryKind.IDEA, text = "让记录成为日常习惯")), { confirmed++ }, {}, {}, { _, _ -> }, { _, _ -> })
                    }
                }
            }
        }
        for (width in listOf(360, 900)) {
            compose.runOnIdle { size.value = DpSize(width.dp, 700.dp) }
            compose.onNodeWithTag("confirm-memory-memory").assertExists()
            screenshot("memory-library-$width")
        }
        compose.runOnIdle {
            size.value = DpSize(360.dp, 700.dp)
            scale.value = 1.5f
        }
        compose.onNodeWithTag("confirm-memory-memory").performClick()
        assertEquals(1, confirmed)
        screenshot("memory-library-large-font")
    }

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
