package dev.local.record.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTest {
    private val requested = RecordingEvent.Requested(1_000, "Asia/Shanghai")

    @Test
    fun replayMatchesLifecycleIncludingPause() {
        val history = listOf(
            requested,
            RecordingEvent.Started,
            RecordingEvent.Paused,
            RecordingEvent.Resumed,
            RecordingEvent.Saved("r.m4a", 4_200)
        )
        var live = Recording("r")
        history.forEach {
            validate(live, it)
            live = evolve(live, it)
        }
        val replay = history.fold(Recording("r"), ::evolve)
        assertEquals(live, replay)
        assertEquals(5, replay.version)
        assertEquals(RecordingStatus.SAVED, replay.status)
        assertEquals("Asia/Shanghai", replay.zone)
    }

    @Test
    fun startRequestIsNotActualCapture() {
        val state = evolve(Recording("r"), requested)
        assertEquals(RecordingStatus.REQUESTED, state.status)
        assertNull(state.fileName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotResumeWithoutPause() {
        validate(evolve(evolve(Recording("r"), requested), RecordingEvent.Started), RecordingEvent.Resumed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSaveEmptyAudio() {
        validate(evolve(Recording("r"), requested), RecordingEvent.Saved("r.m4a", 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun terminalStateCannotStartAgain() {
        val saved = evolve(evolve(Recording("r"), requested), RecordingEvent.Saved("r.m4a", 1_000))
        validate(saved, RecordingEvent.Started)
    }

    @Test
    fun interruptedRecordingCanReferenceRecoveredAudio() {
        val state = evolve(evolve(Recording("r"), requested), RecordingEvent.Interrupted("r.m4a", 3000, "process died"))
        assertEquals(RecordingStatus.INTERRUPTED, state.status)
        assertEquals("r.m4a", state.fileName)
    }

    @Test
    fun allFactsRoundTripWithStableDiscriminator() {
        val events = listOf(
            requested,
            RecordingEvent.Started,
            RecordingEvent.Paused,
            RecordingEvent.Resumed,
            RecordingEvent.Saved("r.m4a", 300),
            RecordingEvent.Interrupted(null, 0, "lost"),
            RecordingEvent.Failed("denied")
        )
        events.forEach { fact ->
            val payload = Json.encodeToString(RecordingEvent.serializer(), fact)
            assertTrue(payload.contains("Recording"))
            assertEquals(fact, Json.decodeFromString(RecordingEvent.serializer(), payload))
        }
    }
}
