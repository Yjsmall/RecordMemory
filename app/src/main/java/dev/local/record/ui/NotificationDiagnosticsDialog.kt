package dev.local.record.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.record.audio.NotificationDiagnostics

@Composable
internal fun NotificationDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(NotificationDiagnostics.report(context)) }
    var copied by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("录音通知") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notification-report"))
                }
                TextButton(onClick = {
                    report = NotificationDiagnostics.report(context)
                    copied = false
                }) { Text("刷新状态") }
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    settingsUnavailable = runCatching { context.startActivity(intent) }.isFailure
                }) { Text("系统通知设置") }
                if (settingsUnavailable) Text("请从系统设置打开本应用的通知设置", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                report = NotificationDiagnostics.report(context)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("录音通知诊断", report))
                copied = true
            }, modifier = Modifier.testTag("copy-notification-report")) { Text(if (copied) "已复制" else "复制报告") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
