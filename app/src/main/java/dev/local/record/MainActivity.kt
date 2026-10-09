package dev.local.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.local.record.audio.RecordingService
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.settings.AppAppearance
import dev.local.record.ui.LibraryViewModel
import dev.local.record.ui.RecordScreen
import dev.local.record.ui.RecordTheme
import dev.local.record.ui.SettingsViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** Visible widget entry handles while-in-use permission, then auto-starts from RESUMED state. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var graph: AppGraph
    private var pendingStart = false
    private val notificationsAllowed = MutableStateFlow(true)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingStart = true
            attemptStart()
        } else {
            graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "未获得麦克风权限。请点击开始重试，或在系统应用设置中允许麦克风。")
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed.value = canNotify()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: (intent.action == RecordingService.START)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) attemptStart()
            }
        )
        enableEdgeToEdge()
        val model = ViewModelProvider(this, LibraryViewModel.Factory(applicationContext, graph))[LibraryViewModel::class.java]
        val settingsModel = ViewModelProvider(this, SettingsViewModel.Factory(graph.settingsRepository))[SettingsViewModel::class.java]
        setContent {
            val recordings by model.recordings.collectAsStateWithLifecycle()
            val session by model.session.collectAsStateWithLifecycle()
            val playback by model.playback.collectAsStateWithLifecycle()
            val ready by model.ready.collectAsStateWithLifecycle()
            val problem by model.problem.collectAsStateWithLifecycle()
            val notify by notificationsAllowed.collectAsStateWithLifecycle()
            val insights by model.insights.collectAsStateWithLifecycle()
            val jobs by model.jobs.collectAsStateWithLifecycle()
            val memories by model.memories.collectAsStateWithLifecycle()
            val settingsState by settingsModel.state.collectAsStateWithLifecycle()
            RecordTheme(
                appearance = settingsState.configuration?.appearance ?: AppAppearance.SYSTEM,
                dynamicColors = settingsState.configuration?.dynamicColors == true
            ) {
                RecordScreen(
                    recordings, session, playback, ready, problem, notify,
                    onStart = {
                        pendingStart = true
                        attemptStart()
                    },
                    onPause = { send(RecordingService.PAUSE) },
                    onStop = { send(RecordingService.STOP) },
                    onPlay = model::play, onSeek = model::seek,
                    onNotifications = ::requestNotifications,
                    settingsState = settingsState,
                    settingsModel = settingsModel,
                    onDelete = model::delete,
                    insights = insights,
                    jobs = jobs,
                    memories = memories,
                    onTranscribe = model::transcribe,
                    onGenerate = model::generate,
                    onSaveTranscript = model::saveTranscript,
                    onSaveTitle = model::saveTitle,
                    onSaveSummary = model::saveSummary,
                    onAcceptTitle = model::acceptTitle,
                    onAcceptSummary = model::acceptSummary,
                    onConfirmMemory = model::confirmMemory,
                    onForgetMemory = model::forgetMemory,
                    onDisableMemory = model::disableMemory,
                    onCorrectMemory = model::correctMemory,
                    onMergeMemory = model::mergeMemory
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == RecordingService.START) {
            pendingStart = true
            attemptStart()
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed.value = canNotify()
        // Lifecycle reaches RESUMED after onResume returns; onPostResume is the start boundary.
    }

    override fun onPostResume() {
        super.onPostResume()
        attemptStart()
    }

    private fun attemptStart() {
        if (!pendingStart || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        pendingStart = false
        if (graph.session.value.active) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getPreferences(MODE_PRIVATE)
            if (preferences.getBoolean("microphoneAsked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                graph.session.value = SessionState(message = "允许麦克风后，返回并点击开始录音。")
            } else {
                preferences.edit().putBoolean("microphoneAsked", true).apply()
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        send(RecordingService.START)
    }

    private fun send(action: String) {
        try {
            RecordingService.command(this, action)
        } catch (error: Exception) {
            if (!graph.session.value.active) {
                graph.session.value = SessionState(phase = SessionPhase.ERROR, message = "无法启动录音：${error.message}")
            }
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !canNotify()) {
            val preferences = getPreferences(MODE_PRIVATE)
            if (preferences.getBoolean("notificationsAsked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                preferences.edit().putBoolean("notificationsAsked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingStart", pendingStart)
        super.onSaveInstanceState(outState)
    }
}
