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
}
