package dev.local.record.audio

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import dev.local.record.R

/** Best-effort OriginOS adapter; a tagged card is separate from the microphone FGS. */
internal object AtomicIsland {
    const val TAG = "VIVO_SUPERX_TAG"
    const val ID = 101
    private const val SCENE = "TIMER"

    fun supportedDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.lowercase(java.util.Locale.ROOT) in setOf("vivo", "iqoo")
    }

    /** Ask only for this package's timer scene, without bypassing ROM access checks. */
    fun initialize(context: Context) {
        if (!supportedDevice()) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val method = manager.javaClass.getMethod("setSuperXInfosSceneList", List::class.java, List::class.java, List::class.java, List::class.java)
            method.invoke(manager, arrayListOf(SCENE), arrayListOf("true"), arrayListOf(context.packageName), arrayListOf("true"))
        }
    }

    fun extras(context: Context, duration: String, paused: Boolean, revision: Int, open: PendingIntent): Bundle {
        val icon = Icon.createWithResource(context, R.drawable.ic_mic)
        val stop = RecordingService.commandPending(context, RecordingService.STOP, 21)
        val toggle = RecordingService.commandPending(context, if (paused) RecordingService.RESUME else RecordingService.PAUSE, 22)
        val state = if (paused) "已暂停" else "正在录音"
        val buttons = arrayListOf(if (paused) "继续" else "暂停", "停止保存")
        val clicks = arrayListOf(toggle, stop)
        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", "随声记 · $state")
            putCharSequence("notification.superx.baseInfos.content", duration)
            putInt("notification.superx.baseInfos.subInfo", 0)
        }
        val infos = Bundle().apply {
            putInt("notification.superx.infos.btnType", 1)
            putStringArrayList("notification.superx.infos.btnTextList", buttons)
            putParcelableArrayList("notification.superx.infos.btnClickRespList", clicks)
            putIntegerArrayList("notification.superx.infos.btnTextColorList", arrayListOf(0xFF244735.toInt(), 0xFFFFFFFF.toInt()))
            putIntegerArrayList("notification.superx.infos.btnColorList", arrayListOf(0xFFE2EDE4.toInt(), 0xFF315C49.toInt()))
        }
        val shortInfos = Bundle().apply {
            putString("notification.superx.shortInfos.coreInfoShort", duration)
            putString("notification.superx.shortInfos.describeShort", state)
            putParcelable("notification.superx.shortInfos.image", icon)
        }
        val left = Bundle().apply {
            putParcelable("island.superx.leftInfo.icon", icon)
            putString("island.superx.leftInfo.content", duration)
        }
        val right = Bundle().apply {
            putParcelable("island.superx.rightInfo.icon", icon)
            putCharSequence("island.superx.rightInfo.content", if (paused) "暂停" else "录音")
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putBundle("island.superx.leftInfo", left)
            putInt("island.superx.rightTemplate", 4)
            putBundle("island.superx.rightInfo", right)
            putInt("island.superx.template", 8)
            putBundle("island.superx.baseInfos", base)
            putBundle("island.superx.infos", infos)
            putBoolean("island.superx.forceShow", true)
            putBoolean("island.superx.forceShowCard", false)
            putBoolean("island.superx.showBarWhenCard", false)
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", open)
            putBoolean("island.superx.dismissCard", false)
        }
        val capsule = Bundle().apply {
            putParcelable("notification.superx.capsule.icon", icon)
            putString("notification.superx.capsule.content", duration)
            putInt("notification.superx.capsule.state", 1)
            putParcelable("notification.superx.capsule.clickResp", open)
        }
        return Bundle().apply {
            // Operation 0 handles create AND updates; operation 1 can be silently dropped.
            putInt("notification.superx.operation", 0)
            putInt("notification.superx.changedRecord", revision)
            putBoolean("notification.superx.showNotify", false)
            putBoolean("notification.superx.islandNotify", true)
            putBoolean("notification.superx.dismissWhenKill", true)
            putBoolean("notification.superx.sound", false)
            putInt("notification.superx.template", 8)
            putString("notification.superx.scene", SCENE)
            putParcelable("notification.superx.clickResp", open)
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.infos", infos)
            putBundle("notification.superx.shortInfos", shortInfos)
            putBundle("notification.superx.capsule", capsule)
            putBundle("notification.superx.island", island)
        }
    }

    fun endExtras() = Bundle().apply {
        putInt("notification.superx.operation", 2)
        putString("notification.superx.scene", SCENE)
        putBoolean("notification.superx.showNotify", false)
        putInt("notification.superx.changedRecord", Int.MAX_VALUE)
    }
}
