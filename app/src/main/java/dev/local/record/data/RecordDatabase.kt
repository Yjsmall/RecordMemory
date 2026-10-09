package dev.local.record.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingStatus
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["aggregateId", "aggregateVersion"], unique = true),
        Index(value = ["commandId"], unique = true)
    ]
)
data class EventRow(
    @PrimaryKey(autoGenerate = true) val globalPosition: Long = 0,
    val eventId: String,
    val aggregateType: String = "Recording",
    val aggregateId: String,
    val aggregateVersion: Int,
    val eventType: String,
    val schemaVersion: Int = 1,
    val payload: String,
    val occurredAt: Long,
    val recordedAt: Long,
    val correlationId: String,
    val causationId: String,
    val commandId: String
)

@Entity(tableName = "recordings")
data class RecordingRow(
    @PrimaryKey val id: String,
    val version: Int,
    val startedAt: Long,
    val zone: String,
    val status: String,
    val fileName: String?,
    val durationMs: Long,
    val problem: String?
) {
    fun domain() = Recording(id, version, startedAt, zone, RecordingStatus.valueOf(status), fileName, durationMs, problem)

    companion object {
        fun from(state: Recording) = RecordingRow(
            state.id,
            state.version,
            state.startedAt,
            state.zone,
            state.status.name,
            state.fileName,
            state.durationMs,
            state.problem
        )
    }
}

@Entity(tableName = "projection_checkpoint")
data class ProjectionCheckpoint(@PrimaryKey val name: String = "recordings", val modelVersion: Int = 1, val position: Long)

@Dao
interface RecordDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observe(): Flow<List<RecordingRow>>

    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    suspend fun all(): List<RecordingRow>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingRow?

    @Query("SELECT * FROM events WHERE commandId = :commandId")
    suspend fun command(commandId: String): EventRow?

    @Query("SELECT * FROM events ORDER BY globalPosition")
    suspend fun events(): List<EventRow>

    @Query("SELECT MAX(aggregateVersion) FROM events WHERE aggregateId = :id")
    suspend fun version(id: String): Int?

    @Insert
    suspend fun insert(row: EventRow): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun project(row: RecordingRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun checkpoint(row: ProjectionCheckpoint)

    @Query("DELETE FROM recordings")
    suspend fun clearProjection()
}

@Database(entities = [EventRow::class, RecordingRow::class, ProjectionCheckpoint::class], version = 1, exportSchema = true)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun recordings(): RecordDao
}
