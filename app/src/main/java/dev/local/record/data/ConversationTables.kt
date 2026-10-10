package dev.local.record.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.Conversation
import dev.local.record.domain.TurnStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationRow(@PrimaryKey val id: String, val version: Int, val createdAt: Long, val deleted: Boolean) {
    fun domain() = Conversation(id, version, createdAt, deleted)
    companion object {
        fun from(state: Conversation) = ConversationRow(state.id, state.version, state.createdAt, state.deleted)
    }
}

@Entity(tableName = "assistant_turns", indices = [Index(value = ["conversationId", "sequence"], unique = true)])
data class AssistantTurnRow(
    @PrimaryKey val id: String,
    val version: Int,
    val conversationId: String,
    val sequence: Int,
    val status: String,
    val attempt: Int,
    val userContentId: String,
    val contextContentId: String?,
    val replyContentId: String?,
    val userText: String,
    val reply: String,
    val failure: String?
) {
    fun domain() = AssistantTurn(id, version, conversationId, sequence, TurnStatus.valueOf(status), attempt, userContentId, contextContentId, replyContentId, userText, reply, failure)
    companion object {
        fun from(state: AssistantTurn) = AssistantTurnRow(state.id, state.version, state.conversationId, state.sequence, state.status.name, state.attempt, state.userContentId, state.contextContentId, state.replyContentId, state.userText, state.reply, state.failure)
    }
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE deleted = 0 ORDER BY createdAt DESC, id")
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM assistant_turns WHERE status != 'DELETED' ORDER BY conversationId, sequence")
    fun observeTurns(): Flow<List<AssistantTurnRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationRow?

    @Query("SELECT * FROM assistant_turns WHERE id = :id")
    suspend fun turn(id: String): AssistantTurnRow?

    @Query("SELECT * FROM assistant_turns WHERE conversationId = :id ORDER BY sequence")
    suspend fun turns(id: String): List<AssistantTurnRow>

    @Query("SELECT * FROM assistant_turns WHERE status IN ('REQUESTED', 'RUNNING')")
    suspend fun interrupted(): List<AssistantTurnRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConversation(row: ConversationRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTurn(row: AssistantTurnRow)

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    @Query("DELETE FROM assistant_turns")
    suspend fun clearTurns()
}
