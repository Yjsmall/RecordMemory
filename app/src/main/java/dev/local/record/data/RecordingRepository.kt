package dev.local.record.data

import androidx.room.withTransaction
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import dev.local.record.domain.evolve
import dev.local.record.domain.validate
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The single business write boundary. Facts and query state commit in one SQLite transaction. */
class RecordingRepository(private val db: RecordDatabase) {
    private val dao = db.recordings()
    private val json = Json { classDiscriminator = "type" }
    val recordings = dao.observe().map { rows -> rows.map(RecordingRow::domain).filter { it.status != RecordingStatus.DELETED } }

    suspend fun all() = dao.all().map(RecordingRow::domain).filter { it.status != RecordingStatus.DELETED }
    suspend fun deleted() = dao.all().map(RecordingRow::domain).filter { it.status == RecordingStatus.DELETED }
    suspend fun get(id: String) = dao.get(id)?.domain()

    suspend fun append(
        id: String,
        expectedVersion: Int,
        commandId: String,
        fact: RecordingEvent,
        occurredAt: Long,
        recordedAt: Long = occurredAt
    ): Recording = db.withTransaction {
        val payload = json.encodeToString(RecordingEvent.serializer(), fact)
        val prior = dao.command(commandId)
        if (prior != null) {
            require(prior.aggregateId == id && prior.payload == payload) { "Idempotency key reused for a different command" }
            return@withTransaction requireNotNull(dao.get(id)).domain()
        }
        val version = dao.version(id) ?: 0
        check(version == expectedVersion) { "Recording version conflict: expected $expectedVersion, actual $version" }
        val current = dao.get(id)?.domain() ?: Recording(id)
        check(current.version == version) { "Projection needs rebuilding" }
        validate(current, fact)
        val next = evolve(current, fact)
        val position = dao.insert(
            EventRow(
                eventId = UUID.randomUUID().toString(), aggregateId = id, aggregateVersion = next.version,
                eventType = json.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content,
                payload = payload, occurredAt = occurredAt, recordedAt = recordedAt,
                correlationId = id, causationId = commandId, commandId = commandId
            )
        )
        dao.project(RecordingRow.from(next))
        dao.checkpoint(ProjectionCheckpoint(position = position))
        next
    }

    /** Atomically replace only projections. Unknown schemas abort without losing current state. */
    suspend fun rebuild() = db.withTransaction {
        val states = linkedMapOf<String, Recording>()
        val history = dao.events()
        history.forEach { row ->
            require(row.schemaVersion == 1 && row.aggregateType == "Recording") { "Unsupported event schema" }
            val state = states[row.aggregateId] ?: Recording(row.aggregateId)
            check(row.aggregateVersion == state.version + 1) { "Non-contiguous aggregate history" }
            val event = json.decodeFromString(RecordingEvent.serializer(), row.payload)
            check(row.eventType == json.parseToJsonElement(row.payload).jsonObject.getValue("type").jsonPrimitive.content)
            states[row.aggregateId] = evolve(state, event)
        }
        dao.clearProjection()
        states.values.forEach { dao.project(RecordingRow.from(it)) }
        dao.checkpoint(ProjectionCheckpoint(position = history.lastOrNull()?.globalPosition ?: 0))
    }
}
