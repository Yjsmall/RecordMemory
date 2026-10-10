package dev.local.record

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.local.record.agent.BuiltInAgentCatalog
import dev.local.record.ai.AiProcessor
import dev.local.record.ai.AiScheduler
import dev.local.record.ai.AutomaticMemoryLearning
import dev.local.record.ai.MemoryPlanner
import dev.local.record.ai.PersonalAssistant
import dev.local.record.audio.RecordingRecovery
import dev.local.record.audio.SessionState
import dev.local.record.data.ConversationRepository
import dev.local.record.data.MIGRATION_1_2
import dev.local.record.data.MIGRATION_2_3
import dev.local.record.data.MIGRATION_3_4
import dev.local.record.data.MIGRATION_4_5
import dev.local.record.data.MemoryPlanningRepository
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordDatabase
import dev.local.record.data.RecordingRepository
import dev.local.record.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow

@HiltAndroidApp
class RecordApplication : Application() {
    @Inject lateinit var graph: AppGraph
}

/** Process-scoped dependencies and live session; recovery never opens the microphone. */
@Singleton
class AppGraph @Inject constructor(@ApplicationContext context: Context) {
    val database = Room.databaseBuilder(context, RecordDatabase::class.java, "record.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    val repository = RecordingRepository(database)
    val processing = ProcessingRepository(database)
    val settingsRepository = SettingsRepository(context)
    val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
    val processor = AiProcessor(repository, processing, settingsRepository, audioDirectory, context.cacheDir)
    val scheduler = AiScheduler(context, processor)
    val session = MutableStateFlow(SessionState())
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val conversations = ConversationRepository(database)
    val agentCatalog = BuiltInAgentCatalog { context.assets.open(it) }
    val memoryPlanning = MemoryPlanningRepository(database)
    val memoryPlanner = MemoryPlanner(memoryPlanning, conversations, processing, settingsRepository, recoveryScope)
    val automaticLearning = AutomaticMemoryLearning(conversations, memoryPlanning, memoryPlanner, settingsRepository, recoveryScope)
    val assistant = PersonalAssistant(conversations, processing, settingsRepository, recoveryScope, catalog = agentCatalog, onAnswered = automaticLearning::onAnswered)
    private val recovery = recoveryScope.async<List<String>> {
        val messages = RecordingRecovery(repository, audioDirectory).recover(System.currentTimeMillis())
        processing.releaseLeases()
        conversations.recoverInterrupted(System.currentTimeMillis())
        conversations.purgeWithdrawnMemoryContent(System.currentTimeMillis())
        memoryPlanning.recoverInterrupted(System.currentTimeMillis())
        automaticLearning.start()
        scheduler.kick()
        messages
    }

    suspend fun awaitRecovery(): List<String> = recovery.await()
}
