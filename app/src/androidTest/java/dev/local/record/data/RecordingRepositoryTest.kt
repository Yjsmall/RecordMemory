package dev.local.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.record.domain.RecordingEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRepositoryTest {
    private lateinit var db: RecordDatabase
    private lateinit var repository: RecordingRepository
    private val requested = RecordingEvent.Requested(123, "Asia/Shanghai")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
        repository = RecordingRepository(db)
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun duplicateCommandsHaveOneEvent() = runTest {
        repository.append("r", 0, "request", requested, 123)
        repository.append("r", 0, "request", requested, 999)
        assertEquals(1, db.recordings().events().size)
        assertEquals(1, repository.get("r")?.version)
    }

    @Test
    fun rebuildRestoresExactlyAndDoesNotAppendFacts() = runTest {
        repository.append("r", 0, "request", requested, 123)
        repository.append("r", 1, "start", RecordingEvent.Started, 124)
        repository.append("r", 2, "saved", RecordingEvent.Saved("r.m4a", 1000), 125)
        val live = repository.all()
        db.recordings().clearProjection()
        repository.rebuild()
        assertEquals(live, repository.all())
        assertEquals(3, db.recordings().events().size)
    }

    @Test
    fun concurrentOldVersionsPermitOnlyOneWrite() = runTest {
        repository.append("r", 0, "request", requested, 123)
        val results = listOf("one", "two").map { command ->
            async { runCatching { repository.append("r", 1, command, RecordingEvent.Started, 124) } }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(2, db.recordings().events().size)
    }

    @Test
    fun invalidTransitionRollsBackEventAndProjection() = runTest {
        repository.append("r", 0, "request", requested, 123)
        assertTrue(runCatching { repository.append("r", 1, "pause", RecordingEvent.Paused, 124) }.isFailure)
        assertEquals(1, db.recordings().events().size)
        assertEquals(1, repository.get("r")?.version)
    }

    @Test
    fun unknownSchemaLeavesCurrentProjectionIntact() = runTest {
        repository.append("r", 0, "request", requested, 123)
        val previous = repository.all()
        val old = db.recordings().events().single()
        db.recordings().insert(old.copy(globalPosition = 0, eventId = "unknown", aggregateVersion = 2, schemaVersion = 99, commandId = "unknown"))
        assertTrue(runCatching { repository.rebuild() }.isFailure)
        assertEquals(previous, repository.all())
    }

    @Test
    fun idempotencyKeyCannotBeReusedForAnotherRecording() = runTest {
        repository.append("r", 0, "request", requested, 123)
        assertTrue(runCatching { repository.append("other", 0, "request", requested, 123) }.isFailure)
        assertEquals(1, repository.all().size)
    }
}
