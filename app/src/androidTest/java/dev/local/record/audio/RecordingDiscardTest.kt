package dev.local.record.audio

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.EntryPointAccessors
import dev.local.record.MainActivity
import dev.local.record.domain.RecordingStatus
import dev.local.record.widget.RecordingWidget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingDiscardTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun recordingCanBeDeletedWithoutSaving() = deleteCurrentRecording(paused = false)

    @Test
    fun pausedRecordingCanBeDeletedWithoutSaving() = deleteCurrentRecording(paused = true)

    private fun deleteCurrentRecording(paused: Boolean) {
        val context = compose.activity
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingWidget.GraphEntryPoint::class.java).graph()
        compose.onNodeWithTag("start-recording").performScrollTo().performClick()
        compose.waitUntil(15_000) { graph.session.value.phase == SessionPhase.RECORDING && graph.session.value.durationMs >= 300 }
        val id = requireNotNull(graph.session.value.recordingId)
        try {
            if (paused) {
                compose.onNodeWithTag("recording-list").performScrollToNode(hasTestTag("recording-$id"))
                compose.onNodeWithTag("recording-$id").performScrollTo().performClick()
                RecordingService.command(context, RecordingService.PAUSE)
                compose.waitUntil(15_000) { graph.session.value.phase == SessionPhase.PAUSED }
            }
            val pane = if (paused) "recording-detail" else "recording-list"
            compose.onNode(hasTestTag("discard-recording") and hasAnyAncestor(hasTestTag(pane))).performScrollTo().performClick()
            compose.waitUntil(15_000) { !graph.session.value.active }
            assertEquals(RecordingStatus.DELETED, runBlocking { graph.repository.get(id)?.status })
            assertFalse(File(graph.audioDirectory, "$id.m4a.part").exists())
            assertFalse(File(graph.audioDirectory, "$id.m4a").exists())
            runBlocking {
                val history = graph.database.recordings().events().filter { it.aggregateId == id }
                assertTrue(history.none { it.eventType == "RecordingSaved" })
                assertTrue(graph.processing.jobsFor(id).isEmpty())
                graph.repository.rebuild()
                RecordingRecovery(graph.repository, graph.audioDirectory).recover(System.currentTimeMillis())
                assertEquals(RecordingStatus.DELETED, graph.repository.get(id)?.status)
                assertTrue(graph.repository.all().none { it.id == id })
            }
            assertEquals(SessionPhase.IDLE, graph.session.value.phase)
            compose.waitUntil(15_000) { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id in setOf(100, 101, 104) } }
        } finally {
            RecordingService.command(context, RecordingService.DISCARD)
        }
    }
}
