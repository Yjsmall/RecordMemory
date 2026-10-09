package dev.local.record.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.delay

/** AAC-LC mono, 48 kHz / 96 kbps in M4A. Owned only by the microphone foreground service. */
class CaptureEngine(context: Context, private val output: File) {
    private val recorder: MediaRecorder

    @Volatile private var paused = false

    @Volatile private var stopping = false

    @Volatile private var failure: String? = null
    private var elapsedBeforePause = 0L
    private var startedAt = 0L

    init {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setOutputFile(output.absolutePath)
            recorder.setOnErrorListener { _, _, _ -> failure = "系统报告录音采集错误" }
            recorder.prepare()
        } catch (error: Exception) {
            recorder.release()
            throw error
        }
    }

    @Synchronized
    fun pause() {
        recorder.pause()
        elapsedBeforePause += SystemClock.elapsedRealtime() - startedAt
        paused = true
    }

    @Synchronized
    fun resume() {
        recorder.resume()
        startedAt = SystemClock.elapsedRealtime()
        paused = false
    }

    fun stop() {
        stopping = true
    }

    @Synchronized
    fun release() {
        recorder.release()
    }

    private fun elapsed() = synchronized(this) {
        elapsedBeforePause + if (paused) 0 else SystemClock.elapsedRealtime() - startedAt
    }

    /** Start fact follows successful MediaRecorder.start(), never a UI click. */
    suspend fun capture(onFirstSamples: suspend () -> Unit, onLevel: (Long, Float, Boolean) -> Unit) {
        synchronized(this) {
            recorder.start()
            startedAt = SystemClock.elapsedRealtime()
        }
        try {
            onFirstSamples()
            while (!stopping) {
                failure?.let { error(it) }
                val level = synchronized(this) { if (paused) 0f else recorder.maxAmplitude / 32768f }
                val silenced = recorder.activeRecordingConfiguration?.isClientSilenced == true
                onLevel(elapsed(), level, silenced)
                check(output.parentFile?.usableSpace?.let { it > 8 * 1024 * 1024 } == true) { "存储空间不足，录音已中断" }
                delay(100)
            }
        } finally {
            // Finalize playable content even on capture errors or graceful service destruction.
            synchronized(this) { recorder.stop() }
        }
    }
}
