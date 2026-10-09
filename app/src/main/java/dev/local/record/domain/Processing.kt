package dev.local.record.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class JobStatus { REQUESTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, STALE }

enum class MemoryKind(val label: String) {
    PERSON("人物"),
    PROJECT("项目"),
    PREFERENCE("偏好"),
    AGREEMENT("约定"),
    TODO("待办"),
    IDEA("想法")
}

enum class MemoryStatus { CANDIDATE, CONFIRMED, FORGOTTEN, INVALIDATED, MERGED }

enum class TextOrigin { NONE, AI, USER }

@Serializable
sealed interface AiJobEvent {
    @Serializable
    @SerialName("AiJobRequested")
    data class Requested(val recordingId: String, val capability: String, val generation: Int, val sourceContentId: String?) : AiJobEvent

    @Serializable
    @SerialName("AiAttemptStarted")
    data class AttemptStarted(val attempt: Int) : AiJobEvent

    @Serializable
    @SerialName("AiJobCompleted")
    data class Completed(val resultContentId: String) : AiJobEvent

    @Serializable
    @SerialName("AiAttemptFailed")
    data class AttemptFailed(val attempt: Int, val reason: String, val terminal: Boolean) : AiJobEvent

    @Serializable
    @SerialName("AiJobCancelled")
    data object Cancelled : AiJobEvent

    @Serializable
    @SerialName("AiResultMarkedStale")
    data class MarkedStale(val resultContentId: String) : AiJobEvent
}

@Serializable
data class AiJob(
    val id: String,
    val version: Int = 0,
    val recordingId: String = "",
    val capability: String = "",
    val status: JobStatus = JobStatus.REQUESTED,
    val generation: Int = 0,
    val attempt: Int = 0,
    val sourceContentId: String? = null,
    val resultContentId: String? = null,
    val error: String? = null
)

fun evolveJob(state: AiJob, event: AiJobEvent): AiJob {
    val next = when (event) {
        is AiJobEvent.Requested -> state.copy(
            recordingId = event.recordingId,
            capability = event.capability,
            generation = event.generation,
            sourceContentId = event.sourceContentId,
            status = JobStatus.REQUESTED,
            error = null
        )
        is AiJobEvent.AttemptStarted -> state.copy(status = JobStatus.RUNNING, attempt = event.attempt, error = null)
        is AiJobEvent.Completed -> state.copy(status = JobStatus.SUCCEEDED, resultContentId = event.resultContentId, error = null)
        is AiJobEvent.AttemptFailed -> state.copy(
            status = if (event.terminal) JobStatus.FAILED else JobStatus.REQUESTED,
            attempt = event.attempt,
            error = event.reason
        )
        AiJobEvent.Cancelled -> state.copy(status = JobStatus.CANCELLED)
        is AiJobEvent.MarkedStale -> state.copy(status = JobStatus.STALE, resultContentId = event.resultContentId)
    }
    return next.copy(version = state.version + 1)
}

fun validateJob(state: AiJob, event: AiJobEvent) {
    val open = state.status == JobStatus.REQUESTED || state.status == JobStatus.RUNNING
    require(
        when (event) {
            is AiJobEvent.Requested -> state.version == 0 && event.recordingId.isNotBlank() && event.generation > 0
            is AiJobEvent.AttemptStarted -> open && event.attempt == state.attempt + 1
            is AiJobEvent.Completed -> state.status == JobStatus.RUNNING && event.resultContentId.isNotBlank()
            is AiJobEvent.AttemptFailed -> state.status == JobStatus.RUNNING && event.attempt == state.attempt && event.reason.isNotBlank()
            AiJobEvent.Cancelled -> open
            is AiJobEvent.MarkedStale -> state.status == JobStatus.RUNNING && event.resultContentId.isNotBlank()
        }
    ) { "Invalid AI job transition: ${state.status} -> $event" }
}

@Serializable
sealed interface TextEvent {
    @Serializable
    @SerialName("TranscriptSet")
    data class TranscriptSet(val contentId: String, val origin: String) : TextEvent

    @Serializable
    @SerialName("TitleSet")
    data class TitleSet(val contentId: String, val origin: String) : TextEvent

    @Serializable
    @SerialName("TitleSuggested")
    data class TitleSuggested(val contentId: String) : TextEvent

    @Serializable
    @SerialName("SummarySet")
    data class SummarySet(val contentId: String, val origin: String) : TextEvent

    @Serializable
    @SerialName("SummarySuggested")
    data class SummarySuggested(val contentId: String) : TextEvent

    @Serializable
    @SerialName("TextMarkedStale")
    data object MarkedStale : TextEvent

    @Serializable
    @SerialName("TextCleared")
    data object Cleared : TextEvent
}

@Serializable
data class RecordingText(
    val recordingId: String,
    val version: Int = 0,
    val title: String? = null,
    val titleContentId: String? = null,
    val titleOrigin: TextOrigin = TextOrigin.NONE,
    val titleSuggestion: String? = null,
    val titleSuggestionContentId: String? = null,
    val transcript: String? = null,
    val transcriptContentId: String? = null,
    val transcriptOrigin: TextOrigin = TextOrigin.NONE,
    val summary: String? = null,
    val summaryContentId: String? = null,
    val summaryOrigin: TextOrigin = TextOrigin.NONE,
    val summarySuggestion: String? = null,
    val summarySuggestionContentId: String? = null,
    val stale: Boolean = false
)

fun evolveText(state: RecordingText, event: TextEvent, body: String?): RecordingText {
    val next = when (event) {
        is TextEvent.TranscriptSet -> state.copy(
            transcript = body,
            transcriptContentId = event.contentId,
            transcriptOrigin = TextOrigin.valueOf(event.origin),
            stale = event.origin == TextOrigin.USER.name && (state.summary != null || state.titleOrigin == TextOrigin.AI)
        )
        is TextEvent.TitleSet -> state.copy(title = body, titleContentId = event.contentId, titleOrigin = TextOrigin.valueOf(event.origin), titleSuggestion = null, titleSuggestionContentId = null)
        is TextEvent.TitleSuggested -> state.copy(titleSuggestion = body, titleSuggestionContentId = event.contentId)
        is TextEvent.SummarySet -> state.copy(
            summary = body,
            summaryContentId = event.contentId,
            summaryOrigin = TextOrigin.valueOf(event.origin),
            summarySuggestion = null,
            summarySuggestionContentId = null,
            stale = false
        )
        is TextEvent.SummarySuggested -> state.copy(summarySuggestion = body, summarySuggestionContentId = event.contentId, stale = false)
        TextEvent.MarkedStale -> state.copy(stale = true)
        TextEvent.Cleared -> RecordingText(recordingId = state.recordingId)
    }
    return next.copy(version = state.version + 1)
}

fun validateText(state: RecordingText, event: TextEvent) {
    require(
        when (event) {
            is TextEvent.TranscriptSet -> event.contentId.isNotBlank() && event.origin in listOf(TextOrigin.AI.name, TextOrigin.USER.name)
            is TextEvent.TitleSet -> event.contentId.isNotBlank() && event.origin in listOf(TextOrigin.AI.name, TextOrigin.USER.name)
            is TextEvent.TitleSuggested -> state.titleOrigin == TextOrigin.USER && event.contentId.isNotBlank()
            is TextEvent.SummarySet -> event.contentId.isNotBlank() && event.origin in listOf(TextOrigin.AI.name, TextOrigin.USER.name)
            is TextEvent.SummarySuggested -> state.summaryOrigin == TextOrigin.USER && event.contentId.isNotBlank()
            TextEvent.MarkedStale -> state.version > 0
            TextEvent.Cleared -> true
        }
    ) { "Invalid text transition: $event" }
}

/** User-owned title or summary stays; a later model result becomes a suggestion. */
fun titleEventFor(current: RecordingText, contentId: String): TextEvent = if (current.titleOrigin == TextOrigin.USER) {
    TextEvent.TitleSuggested(contentId)
} else {
    TextEvent.TitleSet(contentId, TextOrigin.AI.name)
}

fun summaryEventFor(current: RecordingText, contentId: String): TextEvent = if (current.summaryOrigin == TextOrigin.USER) {
    TextEvent.SummarySuggested(contentId)
} else {
    TextEvent.SummarySet(contentId, TextOrigin.AI.name)
}

@Serializable
sealed interface MemoryEvent {
    @Serializable
    @SerialName("MemoryProposed")
    data class Proposed(val type: String, val contentId: String, val sourceRecordingId: String, val sourceContentId: String) : MemoryEvent

    @Serializable
    @SerialName("MemoryConfirmed")
    data object Confirmed : MemoryEvent

    @Serializable
    @SerialName("MemoryCorrected")
    data class Corrected(val contentId: String) : MemoryEvent

    @Serializable
    @SerialName("MemoryForgotten")
    data class Forgotten(val fingerprint: String) : MemoryEvent

    @Serializable
    @SerialName("MemoryInvalidated")
    data class Invalidated(val fingerprint: String) : MemoryEvent

    @Serializable
    @SerialName("MemoryMerged")
    data class Merged(val intoId: String, val fingerprint: String) : MemoryEvent
}

@Serializable
data class MemoryItem(
    val id: String,
    val version: Int = 0,
    val type: MemoryKind = MemoryKind.IDEA,
    val status: MemoryStatus = MemoryStatus.CANDIDATE,
    val text: String = "",
    val evidence: String = "",
    val contentId: String = "",
    val sourceRecordingId: String = "",
    val sourceContentId: String = "",
    val fingerprint: String = ""
) {
    val visible: Boolean get() = status == MemoryStatus.CANDIDATE || status == MemoryStatus.CONFIRMED
}

fun evolveMemory(state: MemoryItem, event: MemoryEvent, text: String?, evidence: String?): MemoryItem {
    val next = when (event) {
        is MemoryEvent.Proposed -> state.copy(
            type = MemoryKind.valueOf(event.type),
            status = MemoryStatus.CANDIDATE,
            text = text.orEmpty(),
            evidence = evidence.orEmpty(),
            contentId = event.contentId,
            sourceRecordingId = event.sourceRecordingId,
            sourceContentId = event.sourceContentId
        )
        MemoryEvent.Confirmed -> state.copy(status = MemoryStatus.CONFIRMED)
        is MemoryEvent.Corrected -> state.copy(status = MemoryStatus.CONFIRMED, text = text.orEmpty(), evidence = evidence ?: state.evidence, contentId = event.contentId, fingerprint = "")
        is MemoryEvent.Forgotten -> state.copy(status = MemoryStatus.FORGOTTEN, text = "", evidence = "", fingerprint = event.fingerprint)
        is MemoryEvent.Invalidated -> state.copy(status = MemoryStatus.INVALIDATED, text = "", evidence = "", fingerprint = event.fingerprint)
        is MemoryEvent.Merged -> state.copy(status = MemoryStatus.MERGED, text = "", evidence = "", fingerprint = event.fingerprint)
    }
    return next.copy(version = state.version + 1)
}

fun validateMemory(state: MemoryItem, event: MemoryEvent) {
    val active = state.status == MemoryStatus.CANDIDATE || state.status == MemoryStatus.CONFIRMED
    require(
        when (event) {
            is MemoryEvent.Proposed -> state.version == 0 && event.contentId.isNotBlank() && event.sourceRecordingId.isNotBlank()
            MemoryEvent.Confirmed -> state.status == MemoryStatus.CANDIDATE
            is MemoryEvent.Corrected -> active && event.contentId.isNotBlank()
            is MemoryEvent.Forgotten -> active && event.fingerprint.isNotBlank()
            is MemoryEvent.Invalidated -> active && event.fingerprint.isNotBlank()
            is MemoryEvent.Merged -> active && event.intoId.isNotBlank() && event.intoId != state.id && event.fingerprint.isNotBlank()
        }
    ) { "Invalid memory transition: ${state.status} -> $event" }
}

fun normalizeMemory(text: String) = text.replace(Regex("\\s+"), "").lowercase()

/** A rejected or existing item from the same recording must not be proposed again. */
fun memoryFingerprint(text: String): String {
    val key = normalizeMemory(text)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun duplicatesMemory(existing: List<MemoryItem>, recordingId: String, text: String): Boolean {
    val key = normalizeMemory(text)
    if (key.isEmpty()) return true
    val fingerprint = memoryFingerprint(text)
    return existing.any {
        it.sourceRecordingId == recordingId && (it.fingerprint == fingerprint || (it.text.isNotBlank() && normalizeMemory(it.text) == key))
    }
}

fun cleanTitle(raw: String): String {
    val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val stripped = line.removePrefix("标题：").removePrefix("标题:").trim().trim('"', '“', '”', '「', '」', '\'')
    require(stripped.isNotBlank()) { "模型没有返回标题" }
    return stripped.take(40)
}

fun cleanSummary(raw: String): String {
    val text = raw.trim()
    require(text.isNotBlank()) { "模型没有返回总结" }
    return text.take(4_000)
}
