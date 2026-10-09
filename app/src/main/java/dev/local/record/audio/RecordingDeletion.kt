package dev.local.record.audio

import dev.local.record.data.RecordingRepository
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import java.io.File

/** Commit a tombstone first; cleanup can be retried after any filesystem failure or process exit. */
class RecordingDeletion(private val repository: RecordingRepository, private val audioDirectory: File) {
    suspend fun delete(recording: Recording, now: Long) {
        val deleted = repository.append(recording.id, recording.version, "${recording.id}:delete", RecordingEvent.Deleted, now)
        cleanup(deleted)
    }

    suspend fun recover(): List<String> = repository.deleted().mapNotNull { recording ->
        runCatching { cleanup(recording) }.exceptionOrNull()?.let { "已删除录音的音频清理未完成：${it.message}" }
    }

    private fun cleanup(recording: Recording) {
        check(recording.status == RecordingStatus.DELETED)
        // Retain only the private filename in the tombstone projection to retry interrupted cleanup.
        val names = listOfNotNull(recording.fileName, "${recording.id}.m4a", "${recording.id}.m4a.part").distinct()
        names.forEach { name ->
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid private audio filename" }
            val file = File(audioDirectory, name)
            require(file.canonicalFile.parentFile == audioDirectory.canonicalFile) { "Audio file outside private directory" }
            check(!file.exists() || file.delete()) { "录音已从列表移除，但音频清理失败；下次启动将重试" }
        }
    }
}
