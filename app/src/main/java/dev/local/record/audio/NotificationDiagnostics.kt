package dev.local.record.audio

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.lang.reflect.InvocationTargetException

/** Stores only integration outcomes. Never reads notification text, audio, AI settings or keys. */
internal object NotificationDiagnostics {
    private const val PREFS = "notification-diagnostics"
    private const val TAG = "RecordingNotification"

    fun errorType(error: Exception): String = (if (error is InvocationTargetException) error.targetException else error).javaClass.simpleName

    fun record(context: Context, stage: String, outcome: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(stage, null) == outcome) return
        prefs.edit().putString(stage, outcome).apply()
        Log.i(TAG, "$stage: $outcome")
    }

    fun report(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (active, liveUpdates) = try {
            val notifications = manager.activeNotifications
            val recording = notifications.firstOrNull { it.id == 100 && it.tag == null }?.notification
            val receipt = "普通录音=${recording != null}；原子请求=${notifications.any { it.id == AtomicIsland.ID }}"
            val promotion = if (recording == null) {
                "Live Updates 请求=无活动录音通知；格式资格=未查询；实际提升=未查询"
            } else {
                val eligible = promotionProbe { NotificationCompat.hasPromotableCharacteristics(recording) }
                val promoted = recording.flags and Notification.FLAG_PROMOTED_ONGOING != 0
                "Live Updates 请求=${NotificationCompat.isRequestPromotedOngoing(recording)}；格式资格=$eligible；实际提升=$promoted"
            }
            receipt to promotion
        } catch (error: Exception) {
            "查询不可用：${errorType(error)}" to "Live Updates 状态查询不可用：${errorType(error)}"
        }
        return buildString {
            appendLine("随声记 · 录音通知诊断")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("系统版本：${Build.DISPLAY}")
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            appendLine("应用：${context.packageName} / $version")
            appendLine("通知总开关：${manager.areNotificationsEnabled()}")
            appendLine("录音渠道重要性：${manager.getNotificationChannel(RecordingService.CHANNEL)?.importance ?: "未创建"}")
            appendLine("候选场景：${AtomicIsland.SCENE}（尚需 vivo 确认录音场景准入）")
            appendLine("场景查询：${AtomicIsland.sceneStatus(context)}")
            appendLine("最近发送：${prefs.getString("post", "尚未发送原子请求")}")
            appendLine("最近清理：${prefs.getString("end", "尚未清理原子请求")}")
            appendLine("当前系统通知：$active")
            appendLine("Live Updates 系统允许：${promotionProbe { NotificationManagerCompat.from(context).canPostPromotedNotifications() }}")
            appendLine(liveUpdates)
            appendLine("系统接收通知不等于显示胶囊。请返回桌面检查。")
            append("标准 Live Updates 的显示由系统决定；vivo 原生原子通知另需场景准入，通知权限不能代替准入。")
        }
    }

    private fun promotionProbe(query: () -> Boolean): String {
        if (Build.VERSION.SDK_INT < 36) return "系统不支持（API < 36）"
        return try {
            query().toString()
        } catch (error: Exception) {
            "查询不可用：${errorType(error)}"
        } catch (error: LinkageError) {
            // API 36 ROMs may lack methods introduced in a later quarterly platform release.
            "查询不可用：${error.javaClass.simpleName}"
        }
    }
}
