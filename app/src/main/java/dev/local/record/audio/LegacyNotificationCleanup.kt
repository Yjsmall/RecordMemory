package dev.local.record.audio

import android.app.NotificationManager
import android.content.Context

/** Retire old island/probe notifications after an upgrade; leave the recording FGS untouched. */
internal fun clearLegacyRecordingNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    listOf(101, 104).forEach { id ->
        manager.cancel("VIVO_SUPERX_TAG", id)
        manager.cancel(id)
    }
    manager.deleteNotificationChannel("local-atomic-probe")
    listOf("local-atomic-probe", "notification-diagnostics").forEach { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
