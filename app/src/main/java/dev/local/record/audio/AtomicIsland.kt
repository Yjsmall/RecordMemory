package dev.local.record.audio

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import dev.local.record.R

/** Official local SuperX protocol. Scene access is granted by vivo, never by this app. */
internal object AtomicIsland {
    const val TAG = "VIVO_SUPERX_TAG"
    const val ID = 101

    // Provisional scene; vivo must approve the recording use case and its final scene name.
    const val SCENE = "TIMER"

    fun supportedDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.lowercase(java.util.Locale.ROOT) in setOf("vivo", "iqoo")
    }

    /** The read-only probe documented in vivo's technical specification, section 5.6. */
    @SuppressLint("SoonBlockedPrivateApi")
    fun sceneStatus(context: Context): String {
        if (!supportedDevice()) return "非 vivo / iQOO 系统"
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val method = NotificationManager::class.java.getDeclaredMethod("getSceneStatus", String::class.java, String::class.java)
            method.isAccessible = true
            when (method.invoke(manager, context.packageName, SCENE)) {
                true -> "场景开关已开启（不代表胶囊已显示）"
                false -> "场景开关未开启（需核对系统设置及 vivo 准入）"
                else -> "场景查询返回未知类型"
            }
        } catch (error: Exception) {
            "场景查询不可用：${NotificationDiagnostics.errorType(error)}"
        }
    }

    fun extras(context: Context, duration: String, paused: Boolean, revision: Int, open: PendingIntent): Bundle {
        val icon = Icon.createWithResource(context, R.drawable.ic_mic_island)
        val toggleIcon = Icon.createWithResource(context, if (paused) R.drawable.ic_island_resume else R.drawable.ic_island_pause)
        val saveIcon = Icon.createWithResource(context, R.drawable.ic_island_save)
        val toggle = RecordingService.commandPending(context, if (paused) RecordingService.RESUME else RecordingService.PAUSE, 22)
        val stop = RecordingService.commandPending(context, RecordingService.STOP, 21)
        val state = if (paused) "已暂停" else "正在录音"
        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", duration)
            putCharSequence("notification.superx.baseInfos.content", state)
            // Official template 4 supports up to three icons with matching click intents.
            putInt("notification.superx.baseInfos.subInfo", 4)
            putParcelableArrayList("notification.superx.baseInfos.subImageList", arrayListOf(toggleIcon, saveIcon))
            putParcelableArrayList("notification.superx.baseInfos.subInfoClickRespList", arrayListOf(toggle, stop))
        }
        val shortInfos = Bundle().apply {
            putString("notification.superx.shortInfos.coreInfoShort", duration)
            putString("notification.superx.shortInfos.describeShort", state)
            putParcelable("notification.superx.shortInfos.image", icon)
            putParcelable("notification.superx.shortInfos.imageClickResp", open)
        }
        val left = Bundle().apply {
            putParcelable("island.superx.leftInfo.icon", icon)
        }
        val right = Bundle().apply {
            putCharSequence("island.superx.rightInfo.content", if (paused) "暂停 $duration" else duration)
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putBundle("island.superx.leftInfo", left)
            putInt("island.superx.rightTemplate", 4)
            putBundle("island.superx.rightInfo", right)
            putInt("island.superx.template", 4)
            putBundle("island.superx.baseInfos", base)
            putBundle("island.superx.infos", Bundle())
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", open)
        }
        val capsule = Bundle().apply {
            putParcelable("notification.superx.capsule.icon", icon)
            putString("notification.superx.capsule.content", if (paused) "暂停 $duration" else duration)
            putInt("notification.superx.capsule.state", 1)
            putInt("notification.superx.capsule.contentColor", 0xFFFFFFFF.toInt())
            putInt("notification.superx.capsule.bgColor", 0xFF315C49.toInt())
            putParcelable("notification.superx.capsule.clickResp", open)
        }
        return Bundle().apply {
            putInt("notification.superx.operation", if (revision == 1) 0 else 1)
            putInt("notification.superx.changedRecord", revision)
            // An independent microphone FGS notification already supplies the ordinary fallback.
            putBoolean("notification.superx.showNotify", false)
            putInt("notification.superx.displays", 0x111)
            putBoolean("notification.superx.dismissWhenKill", true)
            putBoolean("notification.superx.sound", false)
            putInt("notification.superx.template", 4)
            putString("notification.superx.scene", SCENE)
            putParcelable("notification.superx.clickResp", open)
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.infos", Bundle())
            putBundle("notification.superx.shortInfos", shortInfos)
            putBundle("notification.superx.capsule", capsule)
            putBundle("notification.superx.island", island)
        }
    }
}
