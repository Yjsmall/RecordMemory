package dev.local.record.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.local.record.MainActivity
import org.junit.Rule
import org.junit.Test

class AssistantActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeEntryAndActivityRecreationPreserveConversationDraft() {
        compose.onNodeWithTag("open-assistant").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-input").performTextReplacement("重建后仍保留的对话草稿")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("assistant-input").assertTextContains("重建后仍保留的对话草稿")
        compose.onNodeWithTag("configure-assistant").performScrollTo().performClick()
        compose.onNodeWithText("私人助手").assertIsDisplayed()
        compose.onNodeWithTag("model-name").performScrollTo().assertExists()
    }
}
