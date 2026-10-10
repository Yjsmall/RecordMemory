package dev.local.record.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationDiagnosticsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reportCanBeCopiedRefreshedAndDismissed() {
        var dismissed = false
        compose.setContent { RecordTheme { NotificationDiagnosticsDialog { dismissed = true } } }
        compose.onNodeWithTag("notification-report").assertTextContains("系统接收通知不等于显示胶囊", substring = true)
        compose.onNodeWithTag("copy-notification-report").performClick()
        compose.onNodeWithText("已复制").assertExists()
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertTrue(context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.contains("录音通知诊断") == true)
        }
        compose.onNodeWithText("刷新状态").performScrollTo().performClick()
        compose.onNodeWithText("复制报告").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
