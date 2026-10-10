package dev.local.record.ui

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.local.record.agent.BuiltInAgentCatalog
import dev.local.record.audio.SessionState
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private val id = UUID.randomUUID().toString()
    private val repository = SettingsRepository(context, "agent-ui-$id.bin", "agent-ui-$id", scope)
    private lateinit var model: SettingsViewModel

    private fun content() {
        compose.runOnUiThread { model = ViewModelProvider(store, SettingsViewModel.Factory(repository))[SettingsViewModel::class.java] }
        compose.setContent {
            val state by model.state.collectAsState()
            RecordTheme {
                RecordScreen(emptyList(), SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {}, state, model)
            }
        }
        compose.waitUntil(5_000) { model.state.value.configuration != null }
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitUntil(5_000) { model.state.value.agentSkills.size == 3 }
    }

    @After
    fun close() {
        compose.runOnUiThread { store.clear() }
        scope.cancel()
    }

    @Test
    fun skillTogglePersistsAndPreventsIdLoading() {
        content()
        compose.onNodeWithTag("agent-skills").performScrollTo().performClick()
        compose.onNodeWithTag("skill-enabled-weekly-review").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { model.state.value.agentPreferences.skillStates["weekly-review"] == false }
        compose.onNodeWithTag("skill-enabled-weekly-review").assertIsOff()
        val preferences = runBlocking { repository.settings.first().agent }
        val catalog = BuiltInAgentCatalog(context.assets::open)
        assertFalse(catalog.snapshot(preferences).skills.any { it.id == "weekly-review" })
        assertTrue(runCatching { catalog.loadSkill("weekly-review", preferences) }.isFailure)
        compose.onNodeWithText("返回助手设置").performScrollTo().performClick()
        compose.onNodeWithTag("agent-skills").performScrollTo().performClick()
        compose.onNodeWithTag("skill-enabled-weekly-review").performScrollTo().assertIsOff()
    }

    @Test
    fun automaticCandidatesRequireIndependentMemoryConfiguration() {
        content()
        compose.onNodeWithTag("agent-memory").performScrollTo().performClick()
        compose.onNodeWithTag("agent-auto-learning").performScrollTo().assertIsOff().assertIsNotEnabled()
        runBlocking {
            repository.saveConnection(AiConnection("memory-service"), "synthetic-key")
            repository.saveBinding(CapabilityBinding(AiCapability.MEMORY, "memory-service", "synthetic-memory-model"))
        }
        compose.waitUntil(5_000) { model.state.value.configuration?.binding(AiCapability.MEMORY)?.model == "synthetic-memory-model" }
        compose.onNodeWithTag("agent-auto-learning").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.agentPreferences.autoLearning }
        compose.onNodeWithTag("agent-auto-learning").assertIsOn()
        compose.onNodeWithText("默认产生待审核候选", substring = true).assertExists()
        compose.onNodeWithTag("agent-auto-confirm").performScrollTo().assertIsOff()
        assertTrue(runBlocking { repository.settings.first().agent.autoLearning })
    }

    @Test
    fun automaticCandidatesStayOffWhenMemoryCredentialIsMissing() {
        runBlocking {
            repository.saveConnection(AiConnection("memory-service"), null)
            repository.saveBinding(CapabilityBinding(AiCapability.MEMORY, "memory-service", "synthetic-memory-model"))
        }
        content()
        compose.onNodeWithTag("agent-memory").performScrollTo().performClick()
        compose.onNodeWithTag("agent-auto-learning").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { model.setAutoLearning(true) }
        compose.waitUntil(5_000) { model.state.value.message != null && !model.state.value.busy }
        assertFalse(runBlocking { repository.settings.first().agent.autoLearning })
        compose.onNodeWithTag("agent-auto-learning").performScrollTo().assertIsOff()
    }

    @Test
    fun longIdentityDraftShowsBudgetFeedbackWithoutBlockingStorage() {
        content()
        compose.onNodeWithTag("agent-identity").performScrollTo().performClick()
        compose.onNodeWithTag("agent-soul-editor").performScrollTo().performTextReplacement("简".repeat(6_001))
        compose.onNodeWithTag("agent-soul-budget-warning").performScrollTo().assertExists()
        compose.onNodeWithTag("save-agent-soul").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !model.state.value.soulDirty && !model.state.value.busy }
        assertTrue(runBlocking { repository.settings.first().agent.soul?.length == 6_001 })
    }
}
