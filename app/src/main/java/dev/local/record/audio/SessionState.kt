package dev.local.record.audio

enum class SessionPhase { IDLE, STARTING, RECORDING, PAUSED, SAVING, ERROR }

/** Live state, never inferred from historical RecordingStarted events. */
data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val recordingId: String? = null,
    val durationMs: Long = 0,
    val level: Float = 0f,
    val silenced: Boolean = false,
    val message: String? = null
) {
    val active: Boolean get() = phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.PAUSED, SessionPhase.SAVING)
}
