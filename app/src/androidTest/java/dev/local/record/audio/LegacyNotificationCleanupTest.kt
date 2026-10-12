package dev.local.record.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import dev.local.record.R
import dev.local.record.RecordApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegacyNotificationCleanupTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun startupClearsOldIslandAndProbeWithoutRemovingNormalNotifications() {
        val application = ApplicationProvider.getApplicationContext<RecordApplication>()
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording-live", "正在录音", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("local-atomic-probe", "本地胶囊测试", NotificationManager.IMPORTANCE_LOW))
        val normal = NotificationCompat.Builder(application, "recording-live").setSmallIcon(R.drawable.ic_mic).setContentTitle("合成录音提示").build()
        val probe = NotificationCompat.Builder(application, "local-atomic-probe").setSmallIcon(R.drawable.ic_mic).setContentTitle("合成旧测试").build()

        // Android may add its own grouping summary; inspect the notifications posted by this test.
        fun receipts() = manager.activeNotifications.filter { it.id in setOf(100, 101, 104, 303) }
        try {
            manager.notify(100, normal)
            manager.notify(303, normal)
            manager.notify(101, normal)
            manager.notify("VIVO_SUPERX_TAG", 101, normal)
            manager.notify(104, probe)
            manager.notify("VIVO_SUPERX_TAG", 104, probe)
            application.getSharedPreferences("local-atomic-probe", 0).edit().putBoolean("active", true).commit()
            application.getSharedPreferences("notification-diagnostics", 0).edit().putString("scene", "合成诊断").commit()
            waitUntil { receipts().size == 6 }

            application.onCreate()

            waitUntil { receipts().map { it.id }.toSet() == setOf(100, 303) }
            assertTrue(manager.getNotificationChannel("recording-live") != null)
            assertTrue(manager.getNotificationChannel("local-atomic-probe") == null)
            assertTrue(application.getSharedPreferences("local-atomic-probe", 0).all.isEmpty())
            assertTrue(application.getSharedPreferences("notification-diagnostics", 0).all.isEmpty())
            application.onCreate()
            assertEquals(setOf(100, 303), receipts().map { it.id }.toSet())
        } finally {
            listOf(100, 101, 104, 303).forEach {
                manager.cancel(it)
                manager.cancel("VIVO_SUPERX_TAG", it)
            }
            manager.deleteNotificationChannel("local-atomic-probe")
            application.getSharedPreferences("local-atomic-probe", 0).edit().clear().commit()
            application.getSharedPreferences("notification-diagnostics", 0).edit().clear().commit()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for notification cleanup", predicate())
    }
}
