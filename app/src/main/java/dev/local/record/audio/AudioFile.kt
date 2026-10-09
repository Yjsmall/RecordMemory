package dev.local.record.audio

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Validate finalized M4A before publication. An unfinished MP4 container may be unrecoverable. */
object AudioFile {
    fun duration(file: File): Long? = runCatching {
        require(file.length() > 0)
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
            requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                .also { require(it > 0) }
        } finally {
            metadata.release()
        }
    }.getOrNull()

    fun publish(partial: File, target: File): Long {
        val duration = requireNotNull(duration(partial)) { "录音过短或 M4A 未完成封装，原文件已保留" }
        RandomAccessFile(partial, "rw").use { it.fd.sync() }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return duration
    }
}
