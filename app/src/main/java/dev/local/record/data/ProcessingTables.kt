package dev.local.record.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.local.record.domain.AiJob
import dev.local.record.domain.JobStatus
import dev.local.record.domain.MemoryChange
import dev.local.record.domain.MemoryFact
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.RecordingText
import dev.local.record.domain.TextOrigin
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contents")
data class ContentRow(@PrimaryKey val id: String, val kind: String, val body: String, val createdAt: Long)

@Entity(tableName = "recording_text")
data class RecordingTextRow(
    @PrimaryKey val recordingId: String,
    val version: Int,
    val title: String?,
    val titleContentId: String?,
    val titleOrigin: String,
    val titleSuggestion: String?,
    val titleSuggestionContentId: String?,
    val transcript: String?,
    val transcriptContentId: String?,
    val transcriptOrigin: String,
    val summary: String?,
    val summaryContentId: String?,
    val summaryOrigin: String,
    val summarySuggestion: String?,
    val summarySuggestionContentId: String?,
    val stale: Int
) {
    fun domain() = RecordingText(
        recordingId, version, title, titleContentId, TextOrigin.valueOf(titleOrigin), titleSuggestion, titleSuggestionContentId,
        transcript, transcriptContentId, TextOrigin.valueOf(transcriptOrigin), summary, summaryContentId, TextOrigin.valueOf(summaryOrigin),
        summarySuggestion, summarySuggestionContentId, stale == 1
    )

    companion object {
        fun from(state: RecordingText) = RecordingTextRow(
            state.recordingId, state.version, state.title, state.titleContentId, state.titleOrigin.name, state.titleSuggestion,
            state.titleSuggestionContentId, state.transcript, state.transcriptContentId, state.transcriptOrigin.name, state.summary,
            state.summaryContentId, state.summaryOrigin.name, state.summarySuggestion, state.summarySuggestionContentId, if (state.stale) 1 else 0
        )
    }
}

@Entity(tableName = "ai_jobs")
data class AiJobRow(
    @PrimaryKey val id: String,
    val version: Int,
    val recordingId: String,
    val capability: String,
    val status: String,
    val generation: Int,
    val attempt: Int,
    val sourceContentId: String?,
    val resultContentId: String?,
    val error: String?,
    val leaseUntil: Long
) {
    fun domain() = AiJob(id, version, recordingId, capability, JobStatus.valueOf(status), generation, attempt, sourceContentId, resultContentId, error)

    companion object {
        fun from(state: AiJob, leaseUntil: Long) = AiJobRow(
            state.id, state.version, state.recordingId, state.capability, state.status.name, state.generation, state.attempt,
            state.sourceContentId, state.resultContentId, state.error, leaseUntil
        )
    }
}

@Entity(tableName = "outbox")
data class OutboxRow(@PrimaryKey val jobId: String, val state: String)

@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey val id: String,
    val version: Int,
    val type: String,
    val status: String,
    val text: String,
    val evidence: String,
    val contentId: String,
    val sourceRecordingId: String,
    val sourceContentId: String,
    val fingerprint: String,
    @ColumnInfo(defaultValue = "''") val sourceConversationId: String = "",
    @ColumnInfo(defaultValue = "''") val sourceTurnId: String = "",
    @ColumnInfo(defaultValue = "NULL") val factJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val changeJson: String? = null,
    @ColumnInfo(defaultValue = "''") val suppressionKey: String = "",
    @ColumnInfo(defaultValue = "''") val suppressionLabel: String = ""
) {
    fun domain() = MemoryItem(
        id, version, MemoryKind.valueOf(type), MemoryStatus.valueOf(status), text, evidence, contentId, sourceRecordingId, sourceContentId, fingerprint, sourceConversationId, sourceTurnId,
        factJson?.let { eventJson.decodeFromString<MemoryFact>(it) }, changeJson?.let { eventJson.decodeFromString<MemoryChange>(it) }, suppressionKey, suppressionLabel
    )

    companion object {
        fun from(state: MemoryItem) = MemoryRow(
            state.id, state.version, state.type.name, state.status.name, state.text, state.evidence, state.contentId,
            state.sourceRecordingId, state.sourceContentId, state.fingerprint, state.sourceConversationId, state.sourceTurnId,
            state.fact?.let { eventJson.encodeToString(MemoryFact.serializer(), it) }, state.change?.let { eventJson.encodeToString(MemoryChange.serializer(), it) }, state.suppressionKey, state.suppressionLabel
        )
    }
}

@Dao
interface ProcessingDao {
    @Query("SELECT * FROM recording_text")
    fun observeTexts(): Flow<List<RecordingTextRow>>

    @Query("SELECT * FROM ai_jobs")
    fun observeJobs(): Flow<List<AiJobRow>>

    @Query("SELECT * FROM memories")
    fun observeMemories(): Flow<List<MemoryRow>>

    @Query("SELECT * FROM recording_text WHERE recordingId = :id")
    suspend fun text(id: String): RecordingTextRow?

    @Query("SELECT * FROM ai_jobs WHERE id = :id")
    suspend fun job(id: String): AiJobRow?

    @Query("SELECT * FROM ai_jobs WHERE recordingId = :recordingId")
    suspend fun jobsFor(recordingId: String): List<AiJobRow>

    @Query("SELECT * FROM memories")
    suspend fun memories(): List<MemoryRow>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun memory(id: String): MemoryRow?

    @Query("SELECT * FROM memories WHERE sourceRecordingId = :recordingId")
    suspend fun memoriesFor(recordingId: String): List<MemoryRow>

    @Query("SELECT * FROM contents WHERE id = :id")
    suspend fun content(id: String): ContentRow?

    @Query("SELECT * FROM outbox WHERE state = 'PENDING'")
    suspend fun pending(): List<OutboxRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveContent(row: ContentRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveText(row: RecordingTextRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJob(row: AiJobRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOutbox(row: OutboxRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(row: MemoryRow)

    @Query("DELETE FROM contents WHERE id = :id")
    suspend fun deleteContent(id: String)

    @Query("DELETE FROM recording_text")
    suspend fun clearTexts()

    @Query("DELETE FROM ai_jobs")
    suspend fun clearJobs()

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    @Query("UPDATE ai_jobs SET leaseUntil = 0")
    suspend fun clearLeases()
}
