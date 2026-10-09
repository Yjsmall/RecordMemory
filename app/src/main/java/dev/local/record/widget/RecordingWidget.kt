package dev.local.record.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.local.record.AppGraph
import dev.local.record.R
import dev.local.record.audio.RecordToggleActivity
import dev.local.record.audio.RecordingService
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration

/** RemoteViews scales to the launcher-assigned grid. The service is the shared state owner. */
class RecordingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = runCatching {
            EntryPointAccessors.fromApplication(context, GraphEntryPoint::class.java).graph().session.value
        }.getOrDefault(SessionState())
        updateAll(context, state)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GraphEntryPoint {
        fun graph(): AppGraph
    }

    companion object {
        fun updateAll(context: Context, state: SessionState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordingWidget::class.java))
            val action = if (state.active) {
                RecordingService.commandPending(context, RecordingService.STOP, 2)
            } else {
                PendingIntent.getActivity(
                    context,
                    11,
                    Intent(context, RecordToggleActivity::class.java)
                        .setAction(RecordingService.START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val title = when (state.phase) {
                SessionPhase.RECORDING -> if (state.silenced) "麦克风受限" else "正在录音"
                SessionPhase.PAUSED -> "已暂停"
                SessionPhase.STARTING -> "正在启动"
                SessionPhase.SAVING -> "正在保存"
                SessionPhase.ERROR -> "录音出错"
                SessionPhase.IDLE -> "随声记"
            }
            val label = if (state.active) "停止录音" else "开始录音"
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.recording_widget)
                views.setTextViewText(R.id.widget_title, if (state.active) "$title ${formatDuration(state.durationMs)}" else title)
                views.setTextViewText(R.id.widget_action, label)
                views.setOnClickPendingIntent(R.id.widget_root, action)
                views.setOnClickPendingIntent(R.id.widget_title, action)
                views.setOnClickPendingIntent(R.id.widget_action, action)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
