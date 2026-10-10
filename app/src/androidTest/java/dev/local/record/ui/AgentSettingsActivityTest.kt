package dev.local.record.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import dev.local.record.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun identityDraftSurvivesActivityRecreationAndBackRequiresDiscard() {
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("agent-identity").performScrollTo().performClick()
        compose.onNodeWithTag("agent-soul-editor").performScrollTo().performTextReplacement("折叠与重建后保留的身份草稿")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("agent-soul-editor").performScrollTo().assertTextContains("折叠与重建后保留的身份草稿")
        compose.activityRule.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[SettingsViewModel::class.java]
            assertEquals(true, model.state.value.soulDirty)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("agent-soul-editor").performScrollTo().assertTextContains("折叠与重建后保留的身份草稿")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("agent-identity").performScrollTo().assertExists()
        compose.activityRule.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[SettingsViewModel::class.java]
            assertEquals(null, model.state.value.soulDraft)
            assertEquals(false, model.state.value.soulDirty)
        }
    }
}
