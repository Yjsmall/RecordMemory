package dev.local.record.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.local.record.AppGraph
import dev.local.record.audio.AudioFile
import dev.local.record.audio.RecordingDeletion
import dev.local.record.domain.Recording
import dev.local.record.settings.AiCapability
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackState(
    val id: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val problem: String? = null
)

/** Activity-scoped playback survives configuration recreation, including fold/unfold. */
class LibraryViewModel(context: Context, val graph: AppGraph) : ViewModel() {
    val recordings = graph.repository.recordings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val insights = graph.processing.texts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobs = graph.processing.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memories = graph.processing.memories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val session = graph.session
    val playback = MutableStateFlow(PlaybackState())
    val ready = MutableStateFlow(false)
    val problem = MutableStateFlow<String?>(null)
    private var playbackJob: Job? = null
    private var pendingPlaybackId: String? = null
    private val deletingIds = mutableSetOf<String>()
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playback.value = playback.value.copy(playing = isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                playback.value = playback.value.copy(playing = false, problem = "无法播放：${error.errorCodeName}")
            }
        })
    }

    init {
        viewModelScope.launch {
            try {
                problem.value = graph.awaitRecovery().firstOrNull()
                ready.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem.value = "无法恢复录音库：${error.message}"
            }
        }
        viewModelScope.launch {
            session.collect { if (it.active) player.pause() }
        }
        viewModelScope.launch {
            while (true) {
                playback.value = playback.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.takeIf { it > 0 } ?: playback.value.durationMs
                )
                delay(250)
            }
        }
    }

    fun play(recording: Recording) {
        if (session.value.active || recording.id in deletingIds) return
        if (playback.value.id == recording.id) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            return
        }
        val fileName = recording.fileName ?: return
        val file = File(graph.audioDirectory, fileName)
        playbackJob?.cancel()
        pendingPlaybackId = recording.id
        playbackJob = viewModelScope.launch {
            val valid = withContext(Dispatchers.IO) { AudioFile.duration(file) != null }
            if (!valid) {
                playback.value = PlaybackState(id = recording.id, problem = "音频文件缺失或损坏")
                return@launch
            }
            playback.value = PlaybackState(id = recording.id, durationMs = recording.durationMs)
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
    }

    fun seek(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun transcribe(id: String) = ai { graph.processor.request(id, AiCapability.ASR, System.currentTimeMillis()) }

    fun generate(id: String, capability: AiCapability) = ai { graph.processor.request(id, capability, System.currentTimeMillis()) }

    fun saveTranscript(id: String, value: String) = ai { graph.processing.reviseTranscript(id, value, System.currentTimeMillis()) }

    fun saveTitle(id: String, value: String) = ai { graph.processing.reviseTitle(id, value, System.currentTimeMillis()) }

    fun saveSummary(id: String, value: String) = ai { graph.processing.reviseSummary(id, value, System.currentTimeMillis()) }

    fun acceptTitle(id: String) = ai { graph.processing.acceptTitle(id, System.currentTimeMillis()) }

    fun acceptSummary(id: String) = ai { graph.processing.acceptSummary(id, System.currentTimeMillis()) }

    fun confirmMemory(id: String) = ai { graph.processing.confirmMemory(id, System.currentTimeMillis()) }

    fun forgetMemory(id: String) = ai { graph.processing.forgetMemory(id, System.currentTimeMillis()) }

    fun disableMemory(id: String) = ai { graph.processing.disableMemory(id, System.currentTimeMillis()) }

    fun correctMemory(id: String, value: String) = ai { graph.processing.correctMemory(id, value, System.currentTimeMillis()) }

    fun mergeMemory(sourceId: String, targetId: String) = ai { graph.processing.mergeMemory(sourceId, targetId, System.currentTimeMillis()) }

    private fun ai(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                graph.scheduler.kick()
                problem.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem.value = error.message ?: "处理失败"
            }
        }
    }

    fun delete(recording: Recording) {
        if (!ready.value || session.value.recordingId == recording.id && session.value.active || !deletingIds.add(recording.id)) return
        viewModelScope.launch {
            try {
                if (pendingPlaybackId == recording.id) playbackJob?.cancel()
                if (playback.value.id == recording.id) {
                    player.stop()
                    player.clearMediaItems()
                    playback.value = PlaybackState()
                }
                withContext(Dispatchers.IO) {
                    RecordingDeletion(graph.repository, graph.audioDirectory).delete(recording, System.currentTimeMillis())
                }
                problem.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem.value = "删除录音失败：${error.message}"
            } finally {
                deletingIds.remove(recording.id)
            }
        }
    }

    override fun onCleared() {
        player.release()
    }

    class Factory(private val context: Context, private val graph: AppGraph) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(context, graph) as T
        }
    }
}
