package dev.local.record.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.EntryPointAccessors
import dev.local.record.MainActivity
import dev.local.record.ai.WavAudio
import dev.local.record.data.RecordDatabase
import dev.local.record.data.RecordingRepository
import dev.local.record.domain.RecordingEvent
import dev.local.record.domain.RecordingStatus
import dev.local.record.ui.LibraryViewModel
import dev.local.record.widget.RecordingWidget
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // Launch the actual app-owned widget entry, including its immediate return to the launcher.
        context.startActivity(Intent(context, RecordToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { graph.session.value.phase == SessionPhase.RECORDING }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(RecordingService.START))
        try {
            waitUntil { graph.session.value.phase == SessionPhase.RECORDING }
            val id = requireNotNull(graph.session.value.recordingId)
            // Repeated widget requests must reuse the same recording session.
            context.startActivity(Intent(context, MainActivity::class.java).setAction(RecordingService.START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario.recreate()
            SystemClock.sleep(1_000)
            assertEquals(id, graph.session.value.recordingId)
            val notifications = context.getSystemService(NotificationManager::class.java)
            waitUntil { notifications.activeNotifications.any { it.id == 100 && it.notification.actions?.size == 2 } }
            val live = notifications.activeNotifications.first { it.id == 100 }.notification
            assertNotNull(live.contentIntent)
            assertTrue("Recording must request the standard Live Update surface", NotificationCompat.isRequestPromotedOngoing(live))
            assertNull(NotificationCompat.getShortCriticalText(live))
            if (android.os.Build.VERSION.SDK_INT >= 36) assertTrue(NotificationCompat.hasPromotableCharacteristics(live))
            android.util.Log.i("RecordingLiveUpdatesTest", NotificationDiagnostics.report(context).lineSequence().filter { it.startsWith("Live Updates") }.joinToString("\n"))
            assertTrue(live.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals("暂停", live.actions[0].title.toString())
            // OEM extras must survive Binder transport and their buttons must control the service.
            val card = binderRoundTrip(AtomicIsland.extras(context, formatDuration(graph.session.value.durationMs), false, 1, live.contentIntent))
            assertEquals(0, card.getInt("notification.superx.operation"))
            assertEquals(4, card.getInt("notification.superx.template"))
            assertEquals(0x111, card.getInt("notification.superx.displays"))
            val island = requireNotNull(card.getBundle("notification.superx.island"))
            assertEquals(4, island.getInt("island.superx.template"))
            assertEquals(0, island.getInt("island.superx.islandClick"))
            val capsule = requireNotNull(card.getBundle("notification.superx.capsule"))
            assertEquals(1, capsule.getInt("notification.superx.capsule.state"))
            assertTrue(capsule.getInt("notification.superx.capsule.contentColor") != capsule.getInt("notification.superx.capsule.bgColor"))
            assertEquals(1, AtomicIsland.extras(context, "00:02", false, 2, live.contentIntent).getInt("notification.superx.operation"))
            val base = requireNotNull(card.getBundle("notification.superx.baseInfos"))

            @Suppress("DEPRECATION")
            val controls = requireNotNull(base.getParcelableArrayList<PendingIntent>("notification.superx.baseInfos.subInfoClickRespList"))

            @Suppress("DEPRECATION")
            val icons = requireNotNull(base.getParcelableArrayList<Icon>("notification.superx.baseInfos.subImageList"))
            assertEquals(4, base.getInt("notification.superx.baseInfos.subInfo"))
            assertEquals(2, controls.size)
            assertEquals(controls.size, icons.size)
            controls[0].send()
            waitUntil { graph.session.value.phase == SessionPhase.PAUSED }
            SystemClock.sleep(300)
            val pausedAt = graph.session.value.durationMs
            SystemClock.sleep(500)
            assertEquals(pausedAt, graph.session.value.durationMs)
            waitUntil { notifications.activeNotifications.firstOrNull { it.id == 100 }?.notification?.actions?.firstOrNull()?.title == "继续" }
            val paused = notifications.activeNotifications.first { it.id == 100 }.notification
            assertTrue(NotificationCompat.isRequestPromotedOngoing(paused))
            assertEquals(formatDuration(pausedAt), NotificationCompat.getShortCriticalText(paused))
            assertTrue(!paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            val pausedCard = binderRoundTrip(AtomicIsland.extras(context, formatDuration(pausedAt), true, 2, live.contentIntent))
            val pausedBase = requireNotNull(pausedCard.getBundle("notification.superx.island")?.getBundle("island.superx.baseInfos"))

            @Suppress("DEPRECATION")
            val pausedControls = requireNotNull(pausedBase.getParcelableArrayList<PendingIntent>("notification.superx.baseInfos.subInfoClickRespList"))
            pausedControls[0].send()
            waitUntil { graph.session.value.phase == SessionPhase.RECORDING }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(1_000)
            assertTrue(graph.session.value.durationMs > pausedAt)
            controls[0].send()
            waitUntil { graph.session.value.phase == SessionPhase.PAUSED }
            // A previously generated paused card must still save the paused session.
            pausedControls[1].send()
            waitUntil { !graph.session.value.active }
            val recording = runBlocking { graph.repository.get(id) }
            assertEquals(RecordingStatus.SAVED, recording?.status)
            val file = File(graph.audioDirectory, requireNotNull(recording?.fileName))
            assertNotNull(AudioFile.duration(file))
            waitUntil { notifications.activeNotifications.none { it.id == 100 || it.id == AtomicIsland.ID || it.tag == AtomicIsland.TAG } }
            val wav = File(context.cacheDir, "flow-test.wav")
            try {
                WavAudio.transcode(file, wav)
                val bytes = wav.readBytes()
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
                assertEquals(16_000, header.getInt(24))
                val wavDuration = header.getInt(40) * 1000L / 32_000
                assertTrue(kotlin.math.abs(wavDuration - recording.durationMs) < 200)
            } finally {
                wav.delete()
            }
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

    private fun binderRoundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(PendingIntent::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for recording state", predicate())
    }
}
