package dev.local.record.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

class SettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realActivityRecreationKeepsEditorDraftAndKeyOutOfSavedState() {
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("add-connection").performScrollTo().performClick()
        compose.onNodeWithTag("connection-name").performTextReplacement("重建后保留的草稿")
        compose.onNodeWithTag("api-key").performScrollTo().performTextReplacement("synthetic-recreation-key")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("connection-name").performScrollTo().assertTextContains("重建后保留的草稿")
        compose.activityRule.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[SettingsViewModel::class.java]
            assertEquals("synthetic-recreation-key", model.state.value.connectionDraft?.key)
        }
        compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
        compose.onNodeWithText("放弃修改").performClick()
    }
}
