package dev.local.record.audio

import dev.local.record.data.RecordingRepository
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import java.io.File

/** Reconcile unfinished histories with files once per process, before accepting new capture. */
class RecordingRecovery(private val repository: RecordingRepository, private val audioDirectory: File) {
    suspend fun recover(now: Long) {
        repository.all().filter {
            it.status in setOf(RecordingStatus.REQUESTED, RecordingStatus.RECORDING, RecordingStatus.PAUSED)
        }.forEach { recording ->
            val partial = File(audioDirectory, "${recording.id}.m4a.part")
            val complete = File(audioDirectory, "${recording.id}.m4a")
            val duration = AudioFile.duration(complete)
                ?: if (partial.exists()) runCatching { AudioFile.publish(partial, complete) }.getOrNull() else null
            val fact = if (duration != null) {
                RecordingEvent.Interrupted(complete.name, duration, "上次录音意外中断，已恢复可用的音频")
            } else {
                RecordingEvent.Interrupted(null, 0, "上次录音意外中断，M4A 未完成封装；原文件已保留")
            }
            repository.append(recording.id, recording.version, "${recording.id}:recovery", fact, now)
        }
    }
}
