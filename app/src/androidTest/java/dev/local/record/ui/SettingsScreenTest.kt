package dev.local.record.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.settings.AiCapability
import dev.local.record.settings.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private val id = UUID.randomUUID().toString()
    private val repository = SettingsRepository(context, "ui-test-$id.bin", "ui-test-$id", scope)
    private lateinit var model: SettingsViewModel
    private val dimensions = mutableStateOf(androidx.compose.ui.unit.DpSize(400.dp, 800.dp))
    private val fontScale = mutableStateOf(1f)
    private val live = mutableStateOf(SessionState())
    private var stops = 0

    private fun content(restoration: StateRestorationTester? = null) {
        compose.runOnUiThread { model = ViewModelProvider(store, SettingsViewModel.Factory(repository))[SettingsViewModel::class.java] }
        val ui: @androidx.compose.runtime.Composable () -> Unit = {
            val state by model.state.collectAsState()
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(dimensions.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale.value)) {
                    RecordTheme {
                        RecordScreen(emptyList(), live.value, PlaybackState(), true, null, true, {}, {}, { stops++ }, {}, {}, {}, state, model)
                    }
                }
            }
        }
        if (restoration != null) restoration.setContent(ui) else compose.setContent(ui)
        compose.waitUntil(5_000) { model.state.value.configuration != null }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("add-connection").performScrollTo().performClick()
    }

    @After
    fun close() {
        compose.runOnUiThread { store.clear() }
        scope.cancel()
    }

    @Test
    fun savesConnectionAndIndependentCapabilityThenDeletionUnbinds() {
        content()
        compose.onNodeWithText("DeepSeek · Responses").performScrollTo().performClick()
        compose.onNodeWithText("自定义 · OpenAI 兼容").performClick()
        compose.onNodeWithTag("choice-接口协议").assertDoesNotExist()
        compose.onNodeWithTag("advanced-settings").performScrollTo().performClick()
        compose.onNodeWithTag("choice-接口协议").performScrollTo().assertExists()
        compose.onNodeWithTag("advanced-settings").performScrollTo().performClick()
        compose.onNodeWithTag("connection-name").performTextReplacement("测试服务")
        compose.onNodeWithTag("api-key").performScrollTo().performTextReplacement("synthetic-ui-key")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.connections?.size == 1 }
        compose.onNodeWithTag("capability-ASR").performScrollTo().performClick()
        compose.onNodeWithText("未配置").performClick()
        compose.onNodeWithText("测试服务").performClick()
        compose.onNodeWithTag("model-name").performTextReplacement("speech-test")
        compose.onNodeWithTag("prompt").performScrollTo().performTextReplacement("请保留专有名词")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.binding(AiCapability.ASR)?.model == "speech-test" }
        assertEquals(null, model.state.value.configuration?.binding(AiCapability.TITLE)?.connectionId)
        val connectionId = requireNotNull(model.state.value.configuration?.connections?.first()?.id)
        compose.onNodeWithTag("connection-$connectionId").performScrollTo().performClick()
        compose.onNodeWithText("删除此连接").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.connections?.isEmpty() == true }
        assertEquals(null, model.state.value.configuration?.binding(AiCapability.ASR)?.connectionId)
        assertTrue(runBlocking { repository.settings.first().apiKeys.isEmpty() })
    }

    @Test
    fun draftSurvivesWindowAndStateRestorationAndDiscardRequiresDecision() {
        val restoration = StateRestorationTester(compose)
        content(restoration)
        compose.onNodeWithTag("connection-name").performTextReplacement("折叠草稿")
        compose.onNodeWithTag("api-key").performScrollTo().performTextReplacement("synthetic-draft-key")
        for (width in listOf(900, 360, 610)) {
            compose.runOnIdle { dimensions.value = androidx.compose.ui.unit.DpSize(width.dp, 700.dp) }
            compose.onNodeWithTag("connection-name").performScrollTo().assertTextContains("折叠草稿")
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("connection-name").performScrollTo().assertTextContains("折叠草稿")
        assertEquals("synthetic-draft-key", model.state.value.connectionDraft?.key)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("connection-name").assertTextContains("折叠草稿")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        assertEquals(null, model.state.value.connectionDraft)
        assertTrue(model.state.value.configuration?.connections?.isEmpty() == true)
    }

    @Test
    fun narrowLargeFontSettingsKeepStopAndFieldsReachableWithScreenshots() {
        content()
        compose.runOnIdle {
            dimensions.value = androidx.compose.ui.unit.DpSize(360.dp, 650.dp)
            fontScale.value = 1.5f
            live.value = SessionState(SessionPhase.RECORDING, "live", 8_000)
        }
        compose.onNodeWithTag("api-key").performScrollTo().performTextReplacement("synthetic-key")
        compose.onNodeWithText("停止并保存").performClick()
        assertEquals(1, stops)
        screenshot("settings-narrow-large-font")
        compose.runOnIdle {
            dimensions.value = androidx.compose.ui.unit.DpSize(900.dp, 700.dp)
            fontScale.value = 1f
        }
        compose.onNodeWithTag("connection-name").performScrollTo()
        screenshot("settings-wide")
    }

    @Test
    fun badEndpointStaysInEditorAndShowsValidationInsteadOfSaving() {
        content()
        compose.onNodeWithTag("base-url").performTextReplacement("http://example.com/v1")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("settings-message").performScrollTo().assertTextContains("HTTPS", substring = true)
        assertTrue(model.state.value.configuration?.connections?.isEmpty() == true)
        assertTrue(model.state.value.connectionDraft?.dirty == true)
    }

    @Test
    fun deepseekResponsesPresetProvidesModelsAndIsNotAnAsrProvider() {
        content()
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.connections?.size == 1 }
        compose.onNodeWithTag("capability-TITLE").performScrollTo().performClick()
        compose.onNodeWithText("未配置").performClick()
        compose.onNodeWithText("DeepSeek").performClick()
        compose.onNodeWithTag("model-name").assertTextContains("deepseek-flash")
        compose.onNodeWithTag("model-name-options").performScrollTo().performClick()
        compose.onNodeWithText("deepseek-v4-pro").performClick()
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.binding(AiCapability.TITLE)?.model == "deepseek-v4-pro" }
        assertEquals(dev.local.record.settings.RESPONSES, model.state.value.configuration?.connections?.first()?.protocol)
        compose.onNodeWithTag("capability-ASR").performScrollTo().performClick()
        compose.onNodeWithText("请先返回设置添加连接。").performScrollTo().assertExists()
    }

    @Test
    fun doubaoPresetStoresSeparateSpeechModelAndResourceConfiguration() {
        content()
        compose.onNodeWithText("DeepSeek · Responses").performScrollTo().performClick()
        compose.onNodeWithText("豆包 · 录音文件识别").performClick()
        compose.onNodeWithTag("api-key").performScrollTo().performTextReplacement("synthetic-doubao-key")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.connections?.size == 1 }
        compose.onNodeWithTag("capability-ASR").performScrollTo().performClick()
        compose.onNodeWithText("未配置").performClick()
        compose.onNodeWithText("豆包语音").performClick()
        compose.onNodeWithTag("model-name").assertTextContains("bigmodel")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { model.state.value.configuration?.binding(AiCapability.ASR)?.model == "bigmodel" }
        assertEquals("volc.bigasr.auc_turbo", model.state.value.configuration?.connections?.first()?.doubaoResourceId)
        assertEquals(dev.local.record.settings.DOUBAO_ASR, model.state.value.configuration?.connections?.first()?.protocol)
        assertTrue(runBlocking { repository.settings.first().apiKeys.values.contains("synthetic-doubao-key") })
    }

    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
