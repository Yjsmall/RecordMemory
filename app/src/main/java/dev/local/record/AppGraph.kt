package dev.local.record

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.local.record.audio.RecordingRecovery
import dev.local.record.audio.SessionState
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
    val database = Room.databaseBuilder(context, RecordDatabase::class.java, "record.db").build()
    val repository = RecordingRepository(database)
    val settingsRepository = SettingsRepository(context)
    val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
    val session = MutableStateFlow(SessionState())
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recovery = recoveryScope.async {
        RecordingRecovery(repository, audioDirectory).recover(System.currentTimeMillis())
    }

    suspend fun awaitRecovery() = recovery.await()
}
