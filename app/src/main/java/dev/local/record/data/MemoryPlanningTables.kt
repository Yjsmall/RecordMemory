package dev.local.record.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryPlanningTask
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memory_planning")
data class MemoryPlanningRow(
    @PrimaryKey val id: String,
    val version: Int,
    val turnId: String,
    val requestContentId: String,
    val status: String,
    val candidateCount: Int,
    val failure: String?,
    val createdAt: Long
) {
    fun domain() = MemoryPlanningTask(id, version, turnId, requestContentId, MemoryPlanningStatus.valueOf(status), candidateCount, failure, createdAt)

    companion object {
        fun from(task: MemoryPlanningTask) = MemoryPlanningRow(task.id, task.version, task.turnId, task.requestContentId, task.status.name, task.candidateCount, task.failure, task.createdAt)
    }
}

@Dao
interface MemoryPlanningDao {
    @Query("SELECT * FROM memory_planning")
    fun observe(): Flow<List<MemoryPlanningRow>>

    @Query("SELECT * FROM memory_planning")
    suspend fun all(): List<MemoryPlanningRow>

    @Query("SELECT * FROM memory_planning WHERE id = :id")
    suspend fun get(id: String): MemoryPlanningRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: MemoryPlanningRow)

    @Query("DELETE FROM memory_planning")
    suspend fun clear()
}
