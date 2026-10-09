package dev.local.record.audio

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.local.record.R

/**
 * The small island draws leftInfo.content. Duration has to be that string.
 * Unsupported templates collapse the island window to zero height.
 */
internal object AtomicIsland {
    fun extras(context: Context, duration: String, paused: Boolean, operation: Int): Bundle {
        val icon = Icon.createWithResource(context, R.drawable.ic_mic)
        val stop = RecordingService.commandPending(context, RecordingService.STOP, 21)
        val resume = RecordingService.commandPending(context, RecordingService.RESUME, 22)
        val discard = RecordingService.commandPending(context, RecordingService.DISCARD, 23)
        val buttons = arrayListOf("停止", "恢复", "删除")
        val clicks = arrayListOf(stop, resume, discard)
        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", duration)
            putCharSequence("notification.superx.baseInfos.content", duration)
        }
        val infos = Bundle().apply {
            putString("notification.superx.infos.coreInfo", duration)
            putString("notification.superx.infos.describe", if (paused) "已暂停" else "录音")
            putStringArrayList("notification.superx.infos.btnTextList", buttons)
            putParcelableArrayList("notification.superx.infos.btnClickRespList", clicks)
        }
        val shortInfos = Bundle().apply {
            putString("notification.superx.shortInfos.coreInfoShort", duration)
            putString("notification.superx.shortInfos.describeShort", duration)
        }
        val left = Bundle().apply {
            putParcelable("island.superx.leftInfo.icon", icon)
            putString("island.superx.leftInfo.content", duration)
        }
        val right = Bundle().apply {
            putParcelable("island.superx.rightInfo.icon", icon)
            putString("island.superx.rightInfo.content", duration)
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putBundle("island.superx.leftInfo", left)
            putInt("island.superx.rightTemplate", 4)
            putBundle("island.superx.rightInfo", right)
            putBoolean("island.superx.forceShow", true)
            putBoolean("island.superx.forceShowCard", false)
            putInt("island.superx.islandClick", 0)
            putBoolean("island.superx.dismissCard", false)
        }
        val capsule = Bundle().apply {
            putParcelable("notification.superx.capsule.icon", icon)
            putString("notification.superx.capsule.content", duration)
            putInt("notification.superx.capsule.state", 1)
        }
        return Bundle().apply {
            putInt("notification.superx.operation", operation)
            putBoolean("notification.superx.islandNotify", true)
            putInt("notification.superx.template", 4)
            putString("notification.superx.scene", "SOUND_RECORDER")
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.infos", infos)
            putBundle("notification.superx.shortInfos", shortInfos)
            putBundle("notification.superx.capsule", capsule)
            putBundle("notification.superx.island", island)
        }
    }
}
