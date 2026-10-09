package dev.local.record.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.local.record.AppGraph
import dev.local.record.MainActivity
import dev.local.record.R
import dev.local.record.audio.RecordingService
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration

/** RemoteViews scales to the launcher-assigned grid. The service is the shared state owner. */
class RecordingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = EntryPointAccessors.fromApplication(context, GraphEntryPoint::class.java).graph()
        updateAll(context, graph.session.value)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
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
            ids.forEach { id ->
                val large = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 150
                val views = RemoteViews(context.packageName, R.layout.recording_widget)
                val status = when (state.phase) {
                    SessionPhase.RECORDING -> if (state.silenced) "麦克风受限" else "正在录音"
                    SessionPhase.PAUSED -> "已暂停"
                    SessionPhase.STARTING -> "正在启动"
                    SessionPhase.SAVING -> "正在保存"
                    SessionPhase.ERROR -> "录音出错 · 打开查看"
                    SessionPhase.IDLE -> "随声记"
                }
                views.setTextViewText(R.id.widget_title, if (state.active) "$status ${formatDuration(state.durationMs)}" else status)
                views.setTextViewText(R.id.widget_action, if (state.active) "停止并保存" else "开始录音")
                val open = PendingIntent.getActivity(
                    context,
                    10,
                    Intent(context, MainActivity::class.java).setAction(RecordingService.START),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val stop = RecordingService.commandPending(context, RecordingService.STOP, 2)
                views.setOnClickPendingIntent(R.id.widget_action, if (state.active) stop else open)
                views.setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                views.setViewVisibility(
                    R.id.widget_pause,
                    if (large && state.phase in setOf(SessionPhase.RECORDING, SessionPhase.PAUSED)) View.VISIBLE else View.GONE
                )
                views.setTextViewText(R.id.widget_pause, if (state.phase == SessionPhase.PAUSED) "继续" else "暂停")
                views.setOnClickPendingIntent(R.id.widget_pause, RecordingService.commandPending(context, RecordingService.PAUSE, 1))
                manager.updateAppWidget(id, views)
            }
        }
    }
}
