package dev.local.record.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration
import dev.local.record.domain.AiJob
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingStatus
import dev.local.record.domain.RecordingText
import dev.local.record.settings.AiCapability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable
data object Library : NavKey

@Serializable
data class Detail(val id: String) : NavKey

@Serializable
data object MemoriesPage : NavKey

@Serializable
data class AssistantPage(val conversationId: String? = null) : NavKey

@Serializable
data object HistoryLearningPage : NavKey

@Serializable
data object SettingsPage : NavKey

@Serializable
data class ConnectionPage(val id: String?) : NavKey

@Serializable
data class CapabilityPage(val capability: AiCapability) : NavKey

@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RecordScreen(
    recordings: List<Recording>,
    session: SessionState,
    playback: PlaybackState,
    ready: Boolean,
    problem: String?,
    notificationsAllowed: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPlay: (Recording) -> Unit,
    onSeek: (Long) -> Unit,
    onNotifications: () -> Unit,
    settingsState: SettingsUiState = SettingsUiState(),
    settingsModel: SettingsViewModel? = null,
    onDelete: (Recording) -> Unit = {},
    insights: List<RecordingText> = emptyList(),
    jobs: List<AiJob> = emptyList(),
    memories: List<MemoryItem> = emptyList(),
    onTranscribe: (String) -> Unit = {},
    onGenerate: (String, AiCapability) -> Unit = { _, _ -> },
    onSaveTranscript: (String, String) -> Unit = { _, _ -> },
    onSaveTitle: (String, String) -> Unit = { _, _ -> },
    onSaveSummary: (String, String) -> Unit = { _, _ -> },
    onAcceptTitle: (String) -> Unit = {},
    onAcceptSummary: (String) -> Unit = {},
    onConfirmMemory: (String) -> Unit = {},
    onForgetMemory: (String) -> Unit = {},
    onAllowMemoryRelearning: (String) -> Unit = {},
    onDisableMemory: (String) -> Unit = {},
    onCorrectMemory: (String, String) -> Unit = { _, _ -> },
    onMergeMemory: (String, String) -> Unit = { _, _ -> },
    assistantModel: AssistantViewModel? = null
) {
    val backStack = rememberNavBackStack(Library)
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = recordings.firstOrNull { it.id == deleteId }
    if (pendingDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除这段录音？") },
            text = { Text("${recordingTitle(pendingDelete)}\n音频、转写、总结和来源记忆将被删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    onDelete(pendingDelete)
                    if (backStack.filterIsInstance<Detail>().any { it.id == pendingDelete.id }) {
                        while (backStack.size > 1) backStack.removeLastOrNull()
                    }
                }, modifier = Modifier.testTag("confirm-delete-recording")) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowInfo = currentWindowAdaptiveInfo()
        val windowDirective = calculatePaneScaffoldDirective(windowInfo)
        // A hosting region can be narrower than its Activity window (including large-font layouts).
        val canFitTwoPanes = maxWidth >= maxOf(840.dp, 720.dp * LocalDensity.current.fontScale)
        val directive = windowDirective.copy(maxHorizontalPartitions = minOf(windowDirective.maxHorizontalPartitions, if (canFitTwoPanes) 2 else 1))
        val wide = directive.maxHorizontalPartitions > 1
        val strategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(problem) { problem?.let { snackbar.showSnackbar(it) } }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                sceneStrategy = strategy,
                entryProvider = entryProvider {
                    entry<Library>(
                        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = {
                            Box(Modifier.fillMaxSize().testTag("detail-placeholder"), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    IconBadge(RecordIcons.Wave, size = 80)
                                    Text("选择录音", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        })
                    ) {
                        LibraryPane(
                            recordings, insights, session, ready, problem, notificationsAllowed,
                            onStart, onPause, onStop, onNotifications,
                            selectedId = backStack.filterIsInstance<Detail>().lastOrNull()?.id,
                            onDelete = { deleteId = it.id },
                            onSettings = settingsModel?.let { { backStack.add(SettingsPage) } },
                            onMemories = { backStack.add(MemoriesPage) },
                            onAssistant = { backStack.add(AssistantPage()) },
                            onSearch = { backStack.add(HistoryLearningPage) },
                            onSelect = { id ->
                                while (backStack.size > 1) backStack.removeLastOrNull()
                                backStack.add(Detail(id))
                            }
                        )
                    }
                    entry<Detail>(metadata = ListDetailSceneStrategy.detailPane()) { detail ->
                        DetailPane(
                            recordings.firstOrNull { it.id == detail.id },
                            insights.firstOrNull { it.recordingId == detail.id },
                            jobs.filter { it.recordingId == detail.id },
                            memories,
                            session,
                            playback,
                            showBack = !wide,
                            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            onPlay = onPlay,
                            onSeek = onSeek,
                            onStop = onStop,
                            onSettings = settingsModel?.let { { backStack.add(SettingsPage) } },
                            onTranscribe = onTranscribe,
                            onGenerate = onGenerate,
                            onSaveTranscript = onSaveTranscript,
                            onSaveTitle = onSaveTitle,
                            onSaveSummary = onSaveSummary,
                            onAcceptTitle = onAcceptTitle,
                            onAcceptSummary = onAcceptSummary,
                            onConfirmMemory = onConfirmMemory,
                            onForgetMemory = onForgetMemory,
                            onDisableMemory = onDisableMemory,
                            onCorrectMemory = onCorrectMemory,
                            onMergeMemory = onMergeMemory
                        )
                    }
                    entry<MemoriesPage> {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (backStack.size > 1) backStack.removeLastOrNull() }) { Icon(RecordIcons.Back, "返回录音库") }
                                Text("记忆", style = MaterialTheme.typography.titleLarge)
                            }
                            MemoryLibrary(memories, onConfirmMemory, onForgetMemory, onDisableMemory, onCorrectMemory, onMergeMemory, onOpenSource = { id ->
                                if (id.startsWith("chat:")) {
                                    backStack.add(AssistantPage(id.removePrefix("chat:")))
                                } else if (recordings.any { it.id == id }) {
                                    while (backStack.size > 1) backStack.removeLastOrNull()
                                    backStack.add(Detail(id))
                                }
                            }, onAllowRelearning = onAllowMemoryRelearning)
                        }
                    }
                    entry<AssistantPage> { page ->
                        val model = assistantModel
                        if (model != null) {
                            val assistantState by model.state.collectAsStateWithLifecycle()
                            LaunchedEffect(page.conversationId) { page.conversationId?.takeIf { it != model.state.value.conversationId }?.let(model::select) }
                            AssistantScreen(
                                assistantState, session, { backStack.removeLastOrNull() }, model::draft, model::send, model::retry, model::cancel,
                                model::newConversation, model::select, model::deleteConversation, model::remember, onConfirmMemory, onForgetMemory,
                                { backStack.add(MemoriesPage) }, {
                                    settingsModel?.editBinding(AiCapability.ANSWER)
                                    backStack.add(CapabilityPage(AiCapability.ANSWER))
                                }, onPause, onStop, model::planMemory, model::cancelMemoryPlanning, {
                                    settingsModel?.editBinding(AiCapability.MEMORY)
                                    backStack.add(CapabilityPage(AiCapability.MEMORY))
                                }, onHistorySearch = { backStack.add(HistoryLearningPage) }
                            )
                        }
                    }
                    entry<HistoryLearningPage> {
                        assistantModel?.let { model ->
                            val historyState by model.state.collectAsStateWithLifecycle()
                            HistoryLearningScreen(
                                historyState,
                                onBack = { backStack.removeLastOrNull() },
                                onQuery = model::updateHistory,
                                onSearch = model::searchHistory,
                                onSelect = model::selectHistory,
                                onPlan = model::planHistory,
                                onOpenSource = { contentId ->
                                    model.openHistorySource(contentId, { conversationId ->
                                        backStack.add(AssistantPage(conversationId))
                                    }, { recordingId ->
                                        backStack.add(Detail(recordingId))
                                    })
                                },
                                onMemories = { backStack.add(MemoriesPage) },
                                onCancelTask = model::cancelMemoryPlanning
                            )
                        }
                    }
                    entry<SettingsPage> {
                        settingsModel?.let { model ->
                            SettingsFrame(settingsState.agentPage?.label ?: "设置", settingsState, session, settingsState.soulDirty, {
                                if (settingsState.agentPage != null) model.closeAgentPage() else backStack.removeLastOrNull()
                            }, if (settingsState.soulDirty) ({ model.saveSoul() }) else null, onPause, onStop) {
                                SettingsHome(
                                    settingsState,
                                    model,
                                    onConnection = { id ->
                                        model.editConnection(id)
                                        backStack.add(ConnectionPage(id))
                                    },
                                    onCapability = { capability ->
                                        model.editBinding(capability)
                                        backStack.add(CapabilityPage(capability))
                                    }
                                )
                            }
                        }
                    }
                    entry<ConnectionPage> { page ->
                        settingsModel?.let { model ->
                            LaunchedEffect(page, settingsState.configuration != null) {
                                if (settingsState.configuration != null && settingsState.connectionDraft == null) model.editConnection(page.id)
                            }
                            val back = {
                                model.discardDraft()
                                backStack.removeLastOrNull()
                                Unit
                            }
                            SettingsFrame(
                                if (page.id == null) "添加服务" else "编辑服务",
                                settingsState,
                                session,
                                settingsState.connectionDraft?.dirty == true,
                                back,
                                { model.saveConnection { backStack.removeLastOrNull() } },
                                onPause,
                                onStop
                            ) { ConnectionEditor(settingsState, model) { backStack.removeLastOrNull() } }
                        }
                    }
                    entry<CapabilityPage> { page ->
                        settingsModel?.let { model ->
                            LaunchedEffect(page, settingsState.configuration != null) {
                                if (settingsState.configuration != null && settingsState.bindingDraft == null) model.editBinding(page.capability)
                            }
                            val back = {
                                model.discardDraft()
                                backStack.removeLastOrNull()
                                Unit
                            }
                            SettingsFrame(
                                page.capability.label,
                                settingsState,
                                session,
                                settingsState.bindingDirty,
                                back,
                                { model.saveBinding { backStack.removeLastOrNull() } },
                                onPause,
                                onStop
                            ) { CapabilityEditor(settingsState, model) }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun LibraryPane(
    recordings: List<Recording>,
    insights: List<RecordingText>,
    session: SessionState,
    ready: Boolean,
    problem: String?,
    notificationsAllowed: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNotifications: () -> Unit,
    selectedId: String?,
    onDelete: (Recording) -> Unit,
    onSettings: (() -> Unit)?,
    onMemories: () -> Unit,
    onAssistant: () -> Unit,
    onSearch: () -> Unit,
    onSelect: (String) -> Unit
) {
    val scroll = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().testTag("recording-list"),
        state = scroll,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("随声记", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    IconButton(onClick = onMemories, modifier = Modifier.testTag("open-memories")) { Icon(RecordIcons.Memory, "记忆") }
                }
                onSettings?.let {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        IconButton(onClick = it, modifier = Modifier.testTag("open-settings")) { Icon(RecordIcons.Settings, "设置") }
                    }
                }
            }
        }
        item { RecordingControls(session, ready, onStart, onPause, onStop) }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val entries: @Composable () -> Unit = {
                    FeatureEntry("私人助手", "聊想法，梳理计划", RecordIcons.Chat, onAssistant, Modifier.testTag("open-assistant"))
                    FeatureEntry("检索与学习", "查找原文，查看记忆变更", RecordIcons.Search, onSearch, Modifier.testTag("library-history-search"))
                }
                if (maxWidth >= 560.dp && LocalDensity.current.fontScale <= 1.3f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { FeatureEntry("私人助手", "聊想法，梳理计划", RecordIcons.Chat, onAssistant, Modifier.testTag("open-assistant")) }
                        Column(Modifier.weight(1f)) { FeatureEntry("检索与学习", "查找原文，查看记忆变更", RecordIcons.Search, onSearch, Modifier.testTag("library-history-search")) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { entries() }
                }
            }
        }
        if (!notificationsAllowed) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("开启通知，便于后台停止", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onNotifications) { Text("开启") }
                    }
                }
            }
        }
        item { SectionLabel("所有录音", "${recordings.size} 条") }
        if (recordings.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(RecordIcons.Wave, size = 56)
                    Text(if (ready) "第一段录音，从这里开始" else "正在恢复录音…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        recordings.groupBy(::recordingDate).forEach { (date, entries) ->
            item(key = "date-$date") { Text(date, modifier = Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { recording ->
                SwipeRecordingRow(recording.id, ready && recording.status in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED, RecordingStatus.FAILED), { onDelete(recording) }) {
                    Surface(onClick = { onSelect(recording.id) }, shape = RoundedCornerShape(20.dp), color = if (selectedId == recording.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().testTag("recording-${recording.id}")) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(RecordIcons.Wave)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(displayTitle(recording, insights.firstOrNull { it.recordingId == recording.id }), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${formatDuration(recording.durationMs)} · ${if (recording.status == RecordingStatus.SAVED) "M4A" else statusLabel(recording.status)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(RecordIcons.Next, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingControls(session: SessionState, ready: Boolean, onStart: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (session.active) sessionLabel(session.phase) else "新录音", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("M4A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDuration(if (session.active) session.durationMs else 0), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 8.dp).then(if (session.active) Modifier.testTag("live-duration") else Modifier))
            if (session.active) {
                LevelMeter(session.level, session.phase == SessionPhase.RECORDING)
                if (session.silenced) {
                    Text("麦克风受限，请检查麦克风开关", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (session.phase == SessionPhase.RECORDING || session.phase == SessionPhase.PAUSED) {
                    // Column remains usable in narrow split windows and at large font sizes.
                    FilledTonalButton(onClick = onPause, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(if (session.phase == SessionPhase.PAUSED) RecordIcons.Mic else RecordIcons.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (session.phase == SessionPhase.PAUSED) "继续录音" else "暂停录音")
                    }
                }
                Button(onClick = onStop, enabled = session.phase != SessionPhase.SAVING, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(RecordIcons.Stop, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("停止并保存")
                }
            } else {
                session.message?.let { Text(it, color = if (session.phase == SessionPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("本机保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("start-recording")) {
                    Icon(RecordIcons.Mic, null, Modifier.size(22.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("开始录音")
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float, active: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        repeat(24) { index ->
            val height = if (active) 4.dp.toPx() + size.height * level.coerceIn(0f, 1f) * (0.4f + (index % 5) / 8f) else 4.dp.toPx()
            val x = size.width * (index + 0.5f) / 24
            drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun DetailPane(
    recording: Recording?,
    insight: RecordingText?,
    jobs: List<AiJob>,
    memories: List<MemoryItem>,
    session: SessionState,
    playback: PlaybackState,
    showBack: Boolean,
    onBack: () -> Unit,
    onPlay: (Recording) -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    onSettings: (() -> Unit)?,
    onTranscribe: (String) -> Unit,
    onGenerate: (String, AiCapability) -> Unit,
    onSaveTranscript: (String, String) -> Unit,
    onSaveTitle: (String, String) -> Unit,
    onSaveSummary: (String, String) -> Unit,
    onAcceptTitle: (String) -> Unit,
    onAcceptSummary: (String) -> Unit,
    onConfirmMemory: (String) -> Unit,
    onForgetMemory: (String) -> Unit,
    onDisableMemory: (String) -> Unit,
    onCorrectMemory: (String, String) -> Unit,
    onMergeMemory: (String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("recording-detail"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(RecordIcons.Back, "返回录音库") }
            Text("录音详情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            onSettings?.let { IconButton(onClick = it) { Icon(RecordIcons.Settings, "设置") } }
        }
        if (recording == null) {
            Text("录音正在加载…")
            return@Column
        }
        Text(displayTitle(recording, insight), style = MaterialTheme.typography.headlineMedium)
        Text("${recordingDate(recording)} · ${statusLabel(recording.status)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recording.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (session.active) {
            Text("录音中，保存后可播放", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStop, enabled = session.phase != SessionPhase.SAVING) { Text("停止并保存") }
        } else if (recording.fileName != null) {
            val thisPlayback = playback.id == recording.id
            val position = if (thisPlayback) playback.positionMs else 0
            var seeking by remember(recording.id) { mutableFloatStateOf(-1f) }
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(RecordIcons.Wave)
                        Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = if (seeking >= 0) seeking else position.toFloat().coerceIn(0f, recording.durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            onSeek(seeking.toLong())
                            seeking = -1f
                        },
                        valueRange = 0f..recording.durationMs.toFloat().coerceAtLeast(1f),
                        enabled = thisPlayback
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onPlay(recording) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(if (thisPlayback && playback.playing) RecordIcons.Pause else RecordIcons.Play, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (thisPlayback && playback.playing) "暂停播放" else "播放录音")
                    }
                }
            }
            if (thisPlayback) playback.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (recording.fileName != null) Text("M4A · 本机保存", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (recording.fileName != null && !session.active) {
            InsightSection(
                recording.id, insight, jobs, memories, onTranscribe, onGenerate, onSaveTranscript, onSaveTitle, onSaveSummary,
                onAcceptTitle, onAcceptSummary, onConfirmMemory, onForgetMemory, onDisableMemory, onCorrectMemory, onMergeMemory
            )
        }
    }
}

fun displayTitle(recording: Recording, insight: RecordingText?): String = insight?.title?.takeIf { it.isNotBlank() } ?: recordingTitle(recording)

fun recordingTitle(recording: Recording): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm 的录音").withZone(ZoneId.of(recording.zone)).format(Instant.ofEpochMilli(recording.startedAt))
}.getOrDefault("录音")

private fun recordingDate(recording: Recording): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy年MM月dd日").withZone(ZoneId.of(recording.zone)).format(Instant.ofEpochMilli(recording.startedAt))
}.getOrDefault("其他日期")

fun statusLabel(status: RecordingStatus) = when (status) {
    RecordingStatus.REQUESTED -> "启动请求"
    RecordingStatus.RECORDING -> "录制中"
    RecordingStatus.PAUSED -> "已暂停"
    RecordingStatus.SAVED -> "已保存"
    RecordingStatus.INTERRUPTED -> "意外中断"
    RecordingStatus.FAILED -> "未保存"
    RecordingStatus.DELETED -> "已删除"
}

internal fun sessionLabel(phase: SessionPhase) = when (phase) {
    SessionPhase.IDLE -> "随时开始"
    SessionPhase.STARTING -> "正在打开麦克风…"
    SessionPhase.RECORDING -> "正在录音"
    SessionPhase.PAUSED -> "已暂停"
    SessionPhase.SAVING -> "正在保存…"
    SessionPhase.ERROR -> "需要留意"
}

@Preview(name = "外屏", widthDp = 380, heightDp = 800)
@Preview(name = "内屏", widthDp = 900, heightDp = 700)
@Preview(name = "大字体", widthDp = 380, heightDp = 800, fontScale = 1.5f)
@Composable
private fun RecordPreview() {
    RecordTheme {
        RecordScreen(emptyList(), SessionState(), PlaybackState(), true, null, true, {}, {}, {}, {}, {}, {})
    }
}
