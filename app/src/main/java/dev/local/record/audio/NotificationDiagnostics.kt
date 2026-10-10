package dev.local.record.audio

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
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
        val active = try {
            val notifications = manager.activeNotifications
            "普通录音=${notifications.any { it.id == 100 && it.tag == null }}；原子请求=${notifications.any { it.id == AtomicIsland.ID }}"
        } catch (error: Exception) {
            "查询不可用：${errorType(error)}"
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
            appendLine("系统接收通知不等于显示胶囊。请返回桌面检查。")
            append("vivo 官方要求应用上架并申请场景准入；通知权限不能代替准入。")
        }
    }
}
