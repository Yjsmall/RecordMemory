package dev.local.record.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.record.domain.JobStatus
import dev.local.record.domain.TextOrigin
import dev.local.record.widget.RecordingWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingRepositoryTest {
    private lateinit var db: RecordDatabase
    private lateinit var processing: ProcessingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
        processing = ProcessingRepository(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun replayMatchesLiveResultAndDoesNotCreateOutbox() = runTest {
        processing.enqueue("job", "rec", "TITLE", 1, null, 10)
        processing.claim(11, 100)
        processing.applySuccess("job", 1, StoredResult.Title("模型标题"), emptyList(), 12)
        assertEquals("模型标题", processing.text("rec").title)
        assertEquals(TextOrigin.AI, processing.text("rec").titleOrigin)
        assertEquals(0, db.processing().pending().size)
        processing.replay()
        assertEquals(processing.text("rec"), processing.texts.first().single())
        assertEquals(JobStatus.SUCCEEDED, processing.job("job")?.status)
        assertEquals(0, db.processing().pending().size)
    }

    @Test
    fun staleResultDoesNotReplaceUserTitle() = runTest {
        processing.reviseTitle("rec", "人工标题", 1)
        processing.enqueue("job", "rec", "TITLE", 1, "old-transcript", 2)
        processing.claim(3, 100)
        processing.applySuccess("job", 1, StoredResult.Title("迟到标题"), emptyList(), 4)
        val text = processing.text("rec")
        assertEquals("人工标题", text.title)
        assertEquals("迟到标题", text.titleSuggestion)
        assertEquals(JobStatus.STALE, processing.job("job")?.status)
    }

    @Test
    fun widgetProviderIsExportedForLauncherSearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getReceiverInfo(ComponentName(context, RecordingWidget::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.enabled)
        assertEquals("随声记", info.loadLabel(context.packageManager).toString())
    }

    @Test
    fun expiredAttemptCannotPublishOrFailNewAttempt() = runTest {
        processing.enqueue("job", "rec", "ASR", 1, null, 10)
        processing.claim(11, 20)
        assertEquals(2, processing.claim(21, 100)?.attempt)
        val eventsBefore = db.recordings().events().size
        processing.applySuccess("job", 1, StoredResult.Transcript("旧结果"), emptyList(), 22)
        processing.fail("job", 1, "旧失败", true, 23)
        assertEquals(eventsBefore, db.recordings().events().size)
        assertEquals(JobStatus.RUNNING, processing.job("job")?.status)
        processing.applySuccess("job", 2, StoredResult.Transcript("新结果"), emptyList(), 24)
        assertEquals("新结果", processing.text("rec").transcript)
    }

    @Test
    fun delayedRetryRemainsPendingUntilAWorkerCanClaimIt() = runTest {
        processing.enqueue("job", "rec", "ASR", 1, null, 10)
        processing.claim(11, 100)
        processing.fail("job", 1, "暂时断网", false, 12)
        assertEquals(null, processing.claim(13, 100))
        assertTrue(processing.hasPendingWork())
        val retry = processing.claim(30_012, 31_000)!!
        processing.applySuccess("job", retry.attempt, StoredResult.Transcript("完成转写"), emptyList(), 30_013)
        assertTrue(!processing.hasPendingWork())
    }

    @Test
    fun manualTranscriptCancelsInflightAsrAndSurvivesLateCompletionAndReplay() = runTest {
        processing.enqueue("job", "rec", "ASR", 1, null, 10)
        processing.claim(11, 100)
        processing.reviseTranscript("rec", "人工校正", 12)
        processing.applySuccess("job", 1, StoredResult.Transcript("迟到转写"), listOf(FollowUp("follow", "TITLE", 1)), 13)
        processing.fail("job", 1, "迟到失败", true, 14)
        assertEquals(JobStatus.CANCELLED, processing.job("job")?.status)
        assertEquals(null, processing.job("follow"))
        assertEquals("人工校正", processing.text("rec").transcript)
        processing.replay()
        assertEquals("人工校正", processing.text("rec").transcript)
        assertEquals(0, db.processing().pending().size)
    }

    @Test
    fun recordingDeletionPurgesHistoricalBodiesAndReplayDoesNotRestoreThem() = runTest {
        processing.reviseTranscript("rec", "第一版原文", 1)
        val oldTranscript = processing.text("rec").transcriptContentId!!
        processing.reviseTranscript("rec", "第二版原文", 2)
        processing.reviseSummary("rec", "第一版总结", 3)
        val oldSummary = processing.text("rec").summaryContentId!!
        processing.reviseSummary("rec", "第二版总结", 4)
        processing.enqueue("mem", "rec", "MEMORY", 1, processing.text("rec").transcriptContentId, 5)
        processing.claim(6, 100)
        processing.applySuccess("mem", 1, StoredResult.Memories(listOf(MemoryDraft(dev.local.record.domain.MemoryKind.TODO, "准备会议", "第二版原文"))), emptyList(), 7)
        val memory = processing.memories().single()
        processing.correctMemory(memory.id, "明天准备会议", 8)
        processing.onRecordingDeleted("rec", 9)
        assertEquals(null, db.processing().content(oldTranscript))
        assertEquals(null, db.processing().content(oldSummary))
        assertEquals(null, db.processing().content(memory.contentId))
        assertEquals(null, processing.text("rec").transcript)
        assertTrue(processing.memories().none { it.visible })
        processing.replay()
        assertEquals(null, processing.text("rec").summary)
        assertTrue(processing.memories().none { it.visible || it.text.isNotBlank() })
    }

    @Test
    fun forgottenMemoryPurgesOriginalAndCorrectedBodies() = runTest {
        processing.reviseTranscript("rec", "周五发版", 1)
        processing.enqueue("mem", "rec", "MEMORY", 1, processing.text("rec").transcriptContentId, 2)
        processing.claim(3, 100)
        processing.applySuccess("mem", 1, StoredResult.Memories(listOf(MemoryDraft(dev.local.record.domain.MemoryKind.TODO, "周五发版", "周五发版"))), emptyList(), 4)
        val memory = processing.memories().single()
        processing.correctMemory(memory.id, "周六发版", 5)
        val corrected = processing.memory(memory.id)!!.contentId
        processing.forgetMemory(memory.id, 6)
        assertEquals(null, db.processing().content(memory.contentId))
        assertEquals(null, db.processing().content(corrected))
        processing.replay()
        assertEquals("", processing.memory(memory.id)?.text)
        assertTrue(dev.local.record.domain.duplicatesMemory(processing.memories(), "rec", "周六发版"))
    }
}
