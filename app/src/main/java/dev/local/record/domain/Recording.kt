package dev.local.record.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted facts; audio is an immutable private file, never embedded in the log. */
@Serializable
sealed interface RecordingEvent {
    @Serializable
    @SerialName("RecordingStartRequested")
    data class Requested(val at: Long, val zone: String) : RecordingEvent

    @Serializable
    @SerialName("RecordingStarted")
    data object Started : RecordingEvent

    @Serializable
    @SerialName("RecordingPaused")
    data object Paused : RecordingEvent

    @Serializable
    @SerialName("RecordingResumed")
    data object Resumed : RecordingEvent

    @Serializable
    @SerialName("RecordingSaved")
    data class Saved(val fileName: String, val durationMs: Long) : RecordingEvent

    @Serializable
    @SerialName("RecordingInterrupted")
    data class Interrupted(val fileName: String?, val durationMs: Long, val reason: String) : RecordingEvent

    @Serializable
    @SerialName("RecordingFailed")
    data class Failed(val reason: String) : RecordingEvent
}

@Serializable
enum class RecordingStatus { REQUESTED, RECORDING, PAUSED, SAVED, INTERRUPTED, FAILED }

@Serializable
data class Recording(
    val id: String,
    val version: Int = 0,
    val startedAt: Long = 0,
    val zone: String = "UTC",
    val status: RecordingStatus = RecordingStatus.REQUESTED,
    val fileName: String? = null,
    val durationMs: Long = 0,
    val problem: String? = null
)

/** Pure state evolution: deliberately has no microphone, clock, filesystem or scheduler. */
fun evolve(state: Recording, event: RecordingEvent): Recording = when (event) {
    is RecordingEvent.Requested -> state.copy(startedAt = event.at, zone = event.zone)
    RecordingEvent.Started -> state.copy(status = RecordingStatus.RECORDING)
    RecordingEvent.Paused -> state.copy(status = RecordingStatus.PAUSED)
    RecordingEvent.Resumed -> state.copy(status = RecordingStatus.RECORDING)
    is RecordingEvent.Saved -> state.copy(
        status = RecordingStatus.SAVED,
        fileName = event.fileName,
        durationMs = event.durationMs
    )
    is RecordingEvent.Interrupted -> state.copy(
        status = RecordingStatus.INTERRUPTED,
        fileName = event.fileName,
        durationMs = event.durationMs,
        problem = event.reason
    )
    is RecordingEvent.Failed -> state.copy(status = RecordingStatus.FAILED, problem = event.reason)
}.copy(version = state.version + 1)

/** Validate new facts at the command boundary; replay never re-runs current command rules. */
fun validate(state: Recording, event: RecordingEvent) {
    val active = state.status in setOf(RecordingStatus.REQUESTED, RecordingStatus.RECORDING, RecordingStatus.PAUSED)
    require(
        when (event) {
            is RecordingEvent.Requested -> state.version == 0
            RecordingEvent.Started -> state.version > 0 && state.status == RecordingStatus.REQUESTED
            RecordingEvent.Paused -> state.status == RecordingStatus.RECORDING
            RecordingEvent.Resumed -> state.status == RecordingStatus.PAUSED
            is RecordingEvent.Saved -> active && state.version > 0 && event.durationMs > 0
            is RecordingEvent.Interrupted -> active && state.version > 0
            is RecordingEvent.Failed -> active && state.version > 0
        }
    ) { "Invalid recording transition: ${state.status} -> $event" }
}
