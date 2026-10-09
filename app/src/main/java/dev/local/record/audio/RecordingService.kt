package dev.local.record.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.local.record.AppGraph
import dev.local.record.R
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import dev.local.record.widget.RecordingWidget
import java.io.File
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** User-initiated microphone FGS. All entry points serialize commands here. */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var engine: CaptureEngine? = null
    private var capture: Job? = null
    private var lastRefresh = 0L
    private var islandOperation = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "正在录音", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == START && !graph.session.value.active) {
            graph.session.value = SessionState(phase = SessionPhase.STARTING, message = "正在打开麦克风")
            try {
                val serviceType = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                ServiceCompat.startForeground(this, NOTIFICATION, notification(), serviceType)
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "record:microphone"
                ).apply { acquire(12 * 60 * 60 * 1000L) }
            } catch (error: Exception) {
                graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "无法启动录音：${error.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            scope.launch {
                commands.withLock { begin() }
            }
        } else if (action == PAUSE || action == STOP || action == RESUME || action == DISCARD) {
            scope.launch {
                commands.withLock {
                    when (action) {
                        PAUSE -> togglePause()
                        RESUME -> resume()
                        DISCARD -> discardRecording()
                        else -> finishRecording()
                    }
                }
            }
        } else if (!graph.session.value.active) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun begin() {
        val id = UUID.randomUUID().toString()
        graph.session.value = graph.session.value.copy(recordingId = id)
        try {
            graph.awaitRecovery()
            check(graph.audioDirectory.usableSpace > 64 * 1024 * 1024) { "可用存储不足 64 MB" }
            graph.repository.append(
                id,
                0,
                "$id:request",
                RecordingEvent.Requested(System.currentTimeMillis(), ZoneId.systemDefault().id),
                System.currentTimeMillis()
            )
            val newEngine = CaptureEngine(this, File(graph.audioDirectory, "$id.m4a.part"))
            engine = newEngine
            capture = scope.launch(Dispatchers.IO) {
                var problem: String? = null
                try {
                    newEngine.capture(
                        onFirstSamples = {
                            graph.repository.append(id, 1, "$id:start", RecordingEvent.Started, System.currentTimeMillis())
                            graph.session.value = graph.session.value.copy(phase = SessionPhase.RECORDING, message = null)
                        },
                        onLevel = { duration, level, silenced ->
                            graph.session.value = graph.session.value.copy(durationMs = duration, level = level, silenced = silenced)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastRefresh >= 1_000) {
                                lastRefresh = now
                                refresh()
                            }
                        }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    problem = error.message ?: "音频采集失败"
                } finally {
                    newEngine.release()
                }
                if (problem != null) {
                    withContext(Dispatchers.Main) {
                        // Avoid joining this capture coroutine from its own completion handler.
                        scope.launch { commands.withLock { finishRecording(problem) } }
                    }
                }
            }
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val current = graph.repository.get(id)
            if (current != null) {
                runCatching {
                    graph.repository.append(id, current.version, "$id:failed", RecordingEvent.Failed(error.message ?: "启动失败"), System.currentTimeMillis())
                }
            }
            graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "无法开始录音：${error.message}")
            shutdown()
        }
    }

    private suspend fun resume() {
        if (graph.session.value.phase != SessionPhase.PAUSED) return
        togglePause()
    }

    private suspend fun discardRecording() {
        val id = graph.session.value.recordingId
        graph.session.value = graph.session.value.copy(phase = SessionPhase.SAVING, level = 0f)
        engine?.stop()
        capture?.join()
        engine = null
        capture = null
        if (id != null) {
            val current = graph.repository.get(id)
            if (current != null && current.status in setOf(RecordingStatus.REQUESTED, RecordingStatus.RECORDING, RecordingStatus.PAUSED)) {
                val failed = graph.repository.append(id, current.version, "$id:discard", RecordingEvent.Failed("已放弃"), System.currentTimeMillis())
                graph.repository.append(id, failed.version, "$id:discard-delete", RecordingEvent.Deleted, System.currentTimeMillis())
            }
            deletePrivateAudio(id)
        }
        graph.session.value = SessionState(message = "已放弃这次录音")
        shutdown()
    }

    private fun deletePrivateAudio(id: String) {
        listOf("$id.m4a.part", "$id.m4a").forEach { name ->
            val file = File(graph.audioDirectory, name)
            if (file.exists() && file.canonicalFile.parentFile == graph.audioDirectory.canonicalFile) file.delete()
        }
    }

    private suspend fun togglePause() {
        val currentSession = graph.session.value
        if (currentSession.phase !in setOf(SessionPhase.RECORDING, SessionPhase.PAUSED)) return
        val id = currentSession.recordingId ?: return
        try {
            val current = requireNotNull(graph.repository.get(id))
            val pausing = currentSession.phase == SessionPhase.RECORDING
            if (pausing) engine?.pause() else engine?.resume()
            graph.repository.append(
                id,
                current.version,
                "$id:toggle:${current.version}",
                if (pausing) RecordingEvent.Paused else RecordingEvent.Resumed,
                System.currentTimeMillis()
            )
            graph.session.value = graph.session.value.copy(
                phase = if (pausing) SessionPhase.PAUSED else SessionPhase.RECORDING,
                level = 0f,
                silenced = false
            )
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finishRecording(error.message ?: "暂停／继续失败")
        }
    }

    private suspend fun finishRecording(problem: String? = null) {
        val id = graph.session.value.recordingId
        if (id == null || engine == null) {
            shutdown()
            return
        }
        graph.session.value = graph.session.value.copy(phase = SessionPhase.SAVING, level = 0f)
        refresh()
        engine?.stop()
        capture?.join()
        engine = null
        capture = null
        try {
            val partial = File(graph.audioDirectory, "$id.m4a.part")
            val complete = File(graph.audioDirectory, "$id.m4a")
            val duration = withContext(Dispatchers.IO) { AudioFile.publish(partial, complete) }
            val current = requireNotNull(graph.repository.get(id))
            val fact = if (problem == null) {
                RecordingEvent.Saved(complete.name, duration)
            } else {
                RecordingEvent.Interrupted(complete.name, duration, problem)
            }
            graph.repository.append(id, current.version, "$id:finish", fact, System.currentTimeMillis())
            val note = try {
                val queued = graph.processor.enqueueSaved(id, System.currentTimeMillis())
                graph.scheduler.kick()
                if (queued) "已保存录音，正在转写" else "已保存录音"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "已保存录音。自动处理未开始：${error.message}"
            }
            graph.session.value = SessionState(message = if (problem == null) note else "录音中断，已保存可用音频：$problem")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A published file remains recoverable if database commit failed. Do not mark it failed.
            val complete = File(graph.audioDirectory, "$id.m4a")
            if (!complete.exists()) {
                val current = graph.repository.get(id)
                if (current != null) {
                    runCatching {
                        graph.repository.append(id, current.version, "$id:failed", RecordingEvent.Failed(error.message ?: "保存失败"), System.currentTimeMillis())
                    }
                }
            }
            graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "保存失败：${error.message}。已写入文件将保留，请重新打开应用检查。")
        } finally {
            shutdown()
        }
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        RecordingWidget.updateAll(this, graph.session.value)
    }

    private fun notification(): Notification {
        val state = graph.session.value
        val duration = formatDuration(state.durationMs)
        val onIsland = state.phase == SessionPhase.RECORDING || state.phase == SessionPhase.PAUSED
        val operation = islandOperation
        if (onIsland && islandOperation == 0) islandOperation = 1
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (onIsland) duration else "随声记")
            .setContentText(duration)
            .setWhen(System.currentTimeMillis() - state.durationMs)
            .setUsesChronometer(onIsland && state.phase == SessionPhase.RECORDING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止", commandPending(this, STOP, 21))
            .addAction(0, "恢复", commandPending(this, RESUME, 22))
            .addAction(0, "删除", commandPending(this, DISCARD, 23))
        if (onIsland) builder.addExtras(AtomicIsland.extras(this, duration, state.phase == SessionPhase.PAUSED, operation))
        return builder.build()
    }

    private fun shutdown() {
        islandOperation = 0
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        RecordingWidget.updateAll(this, graph.session.value)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.stop()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (graph.session.value.active) {
            graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "录音服务已结束；下次打开时恢复已写入音频")
        }
        RecordingWidget.updateAll(this, graph.session.value)
        super.onDestroy()
    }

    companion object {
        const val START = "dev.local.record.START"
        const val STOP = "dev.local.record.STOP"
        const val PAUSE = "dev.local.record.PAUSE"
        const val RESUME = "dev.local.record.RESUME"
        const val DISCARD = "dev.local.record.DISCARD"
        private const val CHANNEL = "recording-live"
        private const val NOTIFICATION = 100

        fun command(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == START) context.startForegroundService(intent) else context.startService(intent)
        }

        fun commandPending(context: Context, action: String, request: Int): PendingIntent = PendingIntent.getService(
            context,
            request,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
