package dev.local.record.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MemoryPlanningStatus { REQUESTED, RUNNING, COMPLETED, FAILED, CANCELLED }

/** A single authorized paid attempt. Background/manual origin is kept in its deletable input. */
data class MemoryPlanningTask(
    val id: String,
    val version: Int = 0,
    val turnId: String = "",
    val requestContentId: String = "",
    val status: MemoryPlanningStatus = MemoryPlanningStatus.REQUESTED,
    val candidateCount: Int = 0,
    val failure: String? = null,
    val createdAt: Long = 0
)

@Serializable
sealed interface MemoryPlanningEvent {
    @Serializable
    @SerialName("MemoryPlanningRequested")
    data class Requested(val turnId: String, val requestContentId: String, val createdAt: Long = 0, val automatic: Boolean = false) : MemoryPlanningEvent

    @Serializable
    @SerialName("MemoryPlanningSnapshotUpdated")
    data class SnapshotUpdated(val requestContentId: String) : MemoryPlanningEvent

    @Serializable
    @SerialName("MemoryPlanningStarted")
    data object Started : MemoryPlanningEvent

    @Serializable
    @SerialName("MemoryPlanningCompleted")
    data class Completed(val candidateCount: Int) : MemoryPlanningEvent

    @Serializable
    @SerialName("MemoryPlanningFailed")
    data class Failed(val reason: String) : MemoryPlanningEvent

    @Serializable
    @SerialName("MemoryPlanningCancelled")
    data object Cancelled : MemoryPlanningEvent
}

/** Pure projection: replay never extracts facts or schedules a model request. */
fun evolveMemoryPlanning(state: MemoryPlanningTask, event: MemoryPlanningEvent): MemoryPlanningTask {
    val open = state.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING)
    val next = when (event) {
        is MemoryPlanningEvent.Requested -> {
            require(state.version == 0 && event.turnId.isNotBlank() && event.requestContentId.isNotBlank())
            state.copy(turnId = event.turnId, requestContentId = event.requestContentId, createdAt = event.createdAt)
        }
        MemoryPlanningEvent.Started -> {
            require(state.version > 0 && state.status == MemoryPlanningStatus.REQUESTED)
            state.copy(status = MemoryPlanningStatus.RUNNING)
        }
        is MemoryPlanningEvent.SnapshotUpdated -> {
            require(state.version > 0 && state.status == MemoryPlanningStatus.REQUESTED && event.requestContentId.isNotBlank())
            state.copy(requestContentId = event.requestContentId)
        }
        is MemoryPlanningEvent.Completed -> {
            require(state.status == MemoryPlanningStatus.RUNNING && event.candidateCount in 0..3)
            state.copy(status = MemoryPlanningStatus.COMPLETED, candidateCount = event.candidateCount)
        }
        is MemoryPlanningEvent.Failed -> {
            require(state.version > 0 && open && event.reason.isNotBlank())
            state.copy(status = MemoryPlanningStatus.FAILED, failure = event.reason)
        }
        MemoryPlanningEvent.Cancelled -> {
            require(state.version > 0 && open)
            state.copy(status = MemoryPlanningStatus.CANCELLED)
        }
    }
    return next.copy(version = state.version + 1)
}
