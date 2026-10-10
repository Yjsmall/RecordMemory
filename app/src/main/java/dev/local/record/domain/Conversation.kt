package dev.local.record.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class TurnStatus { REQUESTED, RUNNING, ANSWERED, FAILED, CANCELLED, DELETED }

@Serializable
sealed interface ConversationEvent {
    @Serializable
    @SerialName("ConversationCreated")
    data class Created(val createdAt: Long) : ConversationEvent

    @Serializable
    @SerialName("ConversationDeleted")
    data object Deleted : ConversationEvent
}

data class Conversation(val id: String, val version: Int = 0, val createdAt: Long = 0, val deleted: Boolean = false)

fun evolveConversation(state: Conversation, event: ConversationEvent): Conversation = when (event) {
    is ConversationEvent.Created -> state.copy(version = state.version + 1, createdAt = event.createdAt)
    ConversationEvent.Deleted -> state.copy(version = state.version + 1, deleted = true)
}

@Serializable
sealed interface TurnEvent {
    @Serializable
    @SerialName("AssistantTurnRequested")
    data class Requested(val conversationId: String, val sequence: Int, val userContentId: String) : TurnEvent

    @Serializable
    @SerialName("AssistantAttemptStarted")
    data class Started(val attempt: Int, val contextContentId: String) : TurnEvent

    @Serializable
    @SerialName("AssistantTurnAnswered")
    data class Answered(val attempt: Int, val replyContentId: String) : TurnEvent

    @Serializable
    @SerialName("AssistantTurnFailed")
    data class Failed(val attempt: Int, val reason: String) : TurnEvent

    @Serializable
    @SerialName("AssistantTurnCancelled")
    data object Cancelled : TurnEvent

    @Serializable
    @SerialName("AssistantTurnDeleted")
    data object Deleted : TurnEvent

    @Serializable
    @SerialName("AssistantContextWithdrawn")
    data object ContextWithdrawn : TurnEvent
}

data class AssistantTurn(
    val id: String,
    val version: Int = 0,
    val conversationId: String = "",
    val sequence: Int = 0,
    val status: TurnStatus = TurnStatus.REQUESTED,
    val attempt: Int = 0,
    val userContentId: String = "",
    val contextContentId: String? = null,
    val replyContentId: String? = null,
    val userText: String = "",
    val reply: String = "",
    val failure: String? = null
)

fun evolveTurn(state: AssistantTurn, event: TurnEvent, body: String? = null): AssistantTurn {
    val next = when (event) {
        is TurnEvent.Requested -> state.copy(conversationId = event.conversationId, sequence = event.sequence, userContentId = event.userContentId, userText = body.orEmpty())
        is TurnEvent.Started -> state.copy(status = TurnStatus.RUNNING, attempt = event.attempt, contextContentId = event.contextContentId, failure = null)
        is TurnEvent.Answered -> state.copy(status = TurnStatus.ANSWERED, replyContentId = event.replyContentId, reply = body.orEmpty(), failure = null)
        is TurnEvent.Failed -> state.copy(status = TurnStatus.FAILED, failure = event.reason)
        TurnEvent.Cancelled -> state.copy(status = TurnStatus.CANCELLED, failure = null)
        TurnEvent.Deleted -> state.copy(status = TurnStatus.DELETED, userText = "", reply = "", failure = null)
        TurnEvent.ContextWithdrawn -> state.copy(status = TurnStatus.FAILED, reply = "", contextContentId = null, replyContentId = null, failure = "CONTEXT_WITHDRAWN")
    }
    return next.copy(version = state.version + 1)
}

fun validateTurn(state: AssistantTurn, event: TurnEvent) {
    require(
        when (event) {
            is TurnEvent.Requested -> state.version == 0 && event.conversationId.isNotBlank() && event.sequence > 0 && event.userContentId.isNotBlank()
            is TurnEvent.Started -> state.status in setOf(TurnStatus.REQUESTED, TurnStatus.FAILED, TurnStatus.CANCELLED) && event.attempt == state.attempt + 1 && event.contextContentId.isNotBlank()
            is TurnEvent.Answered -> state.status == TurnStatus.RUNNING && event.attempt == state.attempt && event.replyContentId.isNotBlank()
            is TurnEvent.Failed -> state.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) && event.attempt == state.attempt
            TurnEvent.Cancelled -> state.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING)
            TurnEvent.Deleted -> state.version > 0 && state.status != TurnStatus.DELETED
            TurnEvent.ContextWithdrawn -> state.version > 0 && state.status != TurnStatus.DELETED
        }
    ) { "Invalid assistant turn transition" }
}

@Serializable
data class MemoryReference(val id: String, val version: Int)

/** Deletable request provenance; no credentials or duplicate memory bodies. */
@Serializable
data class AssistantContext(
    val connectionId: String,
    val providerName: String,
    val model: String,
    val protocol: String,
    val prompt: String,
    val memories: List<MemoryReference>,
    val historyTurnIds: List<String>,
    val historyMemories: List<MemoryReference> = emptyList()
)
