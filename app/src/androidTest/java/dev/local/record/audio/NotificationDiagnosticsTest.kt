package dev.local.record.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.local.record.R
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationDiagnosticsTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun reflectiveFailuresRevealCauseWithoutExceptionMessage() {
        val cause = SecurityException("private-key-and-recording-text")
        assertEquals("SecurityException", NotificationDiagnostics.errorType(InvocationTargetException(cause)))
        assertEquals("NoSuchMethodException", NotificationDiagnostics.errorType(NoSuchMethodException("private-text")))
    }

    @Test
    fun reportDistinguishesSystemReceiptWithoutIncludingNotificationContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "diagnostics-test"
        manager.createNotificationChannel(NotificationChannel(channel, "诊断测试", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("private-recording-title").setContentText("private-recording-body").build()
            manager.notify(100, notification)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (manager.activeNotifications.none { it.id == 100 } && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
            val report = NotificationDiagnostics.report(context)
            assertTrue(report.contains("普通录音=true"))
            assertTrue(report.contains("系统接收通知不等于显示胶囊"))
            assertFalse(report.contains("private-recording-title"))
            assertFalse(report.contains("private-recording-body"))
            if (!AtomicIsland.supportedDevice()) assertTrue(report.contains("非 vivo / iQOO 系统"))
        } finally {
            manager.cancel(100)
            manager.deleteNotificationChannel(channel)
        }
    }
}
