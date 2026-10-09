package dev.local.record.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.EntryPointAccessors
import dev.local.record.MainActivity
import dev.local.record.data.RecordDatabase
import dev.local.record.data.RecordingRepository
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import dev.local.record.ui.LibraryViewModel
import dev.local.record.widget.RecordingWidget
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun widgetIntentStartsOnceAndSurvivesActivityRecreationAndBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = EntryPointAccessors.fromApplication(context, RecordingWidget.GraphEntryPoint::class.java).graph()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(RecordingService.START))
        try {
            waitUntil { graph.session.value.phase == SessionPhase.RECORDING }
            val id = requireNotNull(graph.session.value.recordingId)
            // Repeated widget requests must reuse the same recording session.
            context.startActivity(Intent(context, MainActivity::class.java).setAction(RecordingService.START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario.recreate()
            SystemClock.sleep(1_000)
            assertEquals(id, graph.session.value.recordingId)
            RecordingService.command(context, RecordingService.PAUSE)
            waitUntil { graph.session.value.phase == SessionPhase.PAUSED }
            SystemClock.sleep(300)
            val pausedAt = graph.session.value.durationMs
            SystemClock.sleep(500)
            assertEquals(pausedAt, graph.session.value.durationMs)
            RecordingService.command(context, RecordingService.PAUSE)
            waitUntil { graph.session.value.phase == SessionPhase.RECORDING }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(1_000)
            assertTrue(graph.session.value.durationMs > pausedAt)
            // Actual RemoteViews stop action sends the same service PendingIntent.
            RecordingService.commandPending(context, RecordingService.STOP, 2).send()
            waitUntil { !graph.session.value.active }
            val recording = runBlocking { graph.repository.get(id) }
            assertEquals(RecordingStatus.SAVED, recording?.status)
            val file = File(graph.audioDirectory, requireNotNull(recording?.fileName))
            assertNotNull(AudioFile.duration(file))
            val before = runBlocking { graph.repository.all() }
            runBlocking { graph.repository.rebuild() }
            assertEquals(before, runBlocking { graph.repository.all() })
            assertTrue(!graph.session.value.active)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            lateinit var model: LibraryViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                model.play(requireNotNull(recording))
            }
            waitUntil { model.playback.value.playing }
            assertEquals(id, model.playback.value.id)
            scenario.onActivity { model.seek(500) }
            waitUntil { model.playback.value.positionMs >= 500 }
            scenario.onActivity { model.play(requireNotNull(recording)) }
            waitUntil { !model.playback.value.playing }
        } finally {
            RecordingService.command(context, RecordingService.STOP)
            scenario.close()
        }
    }

    @Test
    fun validPublishedM4aCanBeRegisteredAfterCrashWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Reuse a real test recording to model death after publication, before the save event.
        val source = EntryPointAccessors.fromApplication(context, RecordingWidget.GraphEntryPoint::class.java).graph()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(RecordingService.START))
        try {
            waitUntil { source.session.value.phase == SessionPhase.RECORDING }
            val id = requireNotNull(source.session.value.recordingId)
            SystemClock.sleep(1200)
            RecordingService.command(context, RecordingService.STOP)
            waitUntil { !source.session.value.active }
            val recording = runBlocking { source.repository.get(id) }
            assertEquals(RecordingStatus.SAVED, recording?.status)
            val sourceFile = File(source.audioDirectory, requireNotNull(recording?.fileName))
            assertTrue(requireNotNull(AudioFile.duration(sourceFile)) > 0)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val repository = RecordingRepository(db)
                val recoveredId = UUID.randomUUID().toString()
                val partialId = UUID.randomUUID().toString()
                val damagedId = UUID.randomUUID().toString()
                val directory = File(context.cacheDir, "recovery-${UUID.randomUUID()}").apply { mkdirs() }
                sourceFile.copyTo(File(directory, "$recoveredId.m4a"))
                sourceFile.copyTo(File(directory, "$partialId.m4a.part"))
                File(directory, "$damagedId.m4a.part").writeBytes(byteArrayOf(0, 1, 2))
                runBlocking {
                    for (aggregate in listOf(recoveredId, partialId, damagedId)) {
                        repository.append(aggregate, 0, "$aggregate:request", RecordingEvent.Requested(1, "UTC"), 1)
                        repository.append(aggregate, 1, "$aggregate:start", RecordingEvent.Started, 2)
                    }
                    RecordingRecovery(repository, directory).recover(3)
                    assertEquals(RecordingStatus.INTERRUPTED, repository.get(recoveredId)?.status)
                    assertNotNull(repository.get(recoveredId)?.fileName)
                    assertNotNull(repository.get(partialId)?.fileName)
                    assertEquals(null, repository.get(damagedId)?.fileName)
                    assertTrue(File(directory, "$damagedId.m4a.part").exists())
                    assertEquals(9, db.recordings().events().size)
                    RecordingRecovery(repository, directory).recover(4)
                    assertEquals(9, db.recordings().events().size)
                }
            } finally {
                db.close()
            }
        } finally {
            scenario.close()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for recording state", predicate())
    }
}
