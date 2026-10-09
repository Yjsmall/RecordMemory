package dev.local.record.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration
import dev.local.record.domain.Recording
import dev.local.record.domain.RecordingStatus
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
    settingsModel: SettingsViewModel? = null
) {
    val backStack = rememberNavBackStack(Library)
    val windowInfo = currentWindowAdaptiveInfo()
    val directive = calculatePaneScaffoldDirective(windowInfo)
    val wide = directive.maxHorizontalPartitions > 1
    val strategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            sceneStrategy = strategy,
            entryProvider = entryProvider {
                entry<Library>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = {
                        Box(Modifier.fillMaxSize().testTag("detail-placeholder"), contentAlignment = Alignment.Center) {
                            Text("选择一条录音，听听当时的想法", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                ) {
                    LibraryPane(
                        recordings, session, ready, problem, notificationsAllowed,
                        onStart, onPause, onStop, onNotifications,
                        onSettings = settingsModel?.let { { backStack.add(SettingsPage) } },
                        onSelect = { id ->
                            while (backStack.size > 1) backStack.removeLastOrNull()
                            backStack.add(Detail(id))
                        }
                    )
                }
                entry<Detail>(metadata = ListDetailSceneStrategy.detailPane()) { detail ->
                    DetailPane(
                        recordings.firstOrNull { it.id == detail.id },
                        session,
                        playback,
                        showBack = !wide,
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        onPlay = onPlay,
                        onSeek = onSeek,
                        onStop = onStop,
                        onSettings = settingsModel?.let { { backStack.add(SettingsPage) } }
                    )
                }
                entry<SettingsPage> {
                    settingsModel?.let { model ->
                        SettingsFrame("设置", settingsState, session, false, { backStack.removeLastOrNull() }, null, onPause, onStop) {
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
                            "连接",
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
                            "能力配置",
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

@Composable
private fun LibraryPane(
    recordings: List<Recording>,
    session: SessionState,
    ready: Boolean,
    problem: String?,
    notificationsAllowed: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: (() -> Unit)?,
    onSelect: (String) -> Unit
) {
    val scroll = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().testTag("recording-list"),
        state = scroll,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("随声记", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    onSettings?.let { TextButton(onClick = it, modifier = Modifier.testTag("open-settings")) { Text("设置") } }
                }
                Text("留住此刻的想法", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { RecordingControls(session, ready, onStart, onPause, onStop) }
        if (!notificationsAllowed) {
            item {
                Column {
                    Text("录音通知未开启。离开页面后，可通过桌面小组件停止录音。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onNotifications) { Text("开启录音通知") }
                }
            }
        }
        problem?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error) } }
        item { Text("录音库 · ${recordings.size}", style = MaterialTheme.typography.titleMedium) }
        if (recordings.isEmpty()) {
            item {
                Text(
                    if (ready) "还没有录音。点击开始，或将「随声记」小组件添加到桌面。" else "正在恢复录音库…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        recordings.groupBy(::recordingDate).forEach { (date, entries) ->
            item(key = "date-$date") { Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { recording ->
                Column {
                    ListItem(
                        headlineContent = { Text(recordingTitle(recording), maxLines = 2) },
                        supportingContent = { Text("${formatDuration(recording.durationMs)} · ${statusLabel(recording.status)}") },
                        modifier = Modifier.clickable { onSelect(recording.id) }.testTag("recording-${recording.id}")
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item { Text("音频保存在本机 · M4A", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun RecordingControls(session: SessionState, ready: Boolean, onStart: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                sessionLabel(session.phase),
                style = MaterialTheme.typography.titleMedium
            )
            if (session.active) {
                Text(formatDuration(session.durationMs), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("live-duration"))
                LevelMeter(session.level, session.phase == SessionPhase.RECORDING)
                if (session.silenced) {
                    Text("麦克风受限，可能正在录入静音。请检查麦克风开关或其他录音应用。", color = MaterialTheme.colorScheme.error)
                }
                if (session.phase == SessionPhase.RECORDING || session.phase == SessionPhase.PAUSED) {
                    // Column remains usable in narrow split windows and at large font sizes.
                    OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Text(if (session.phase == SessionPhase.PAUSED) "继续录音" else "暂停录音")
                    }
                }
                Button(onClick = onStop, enabled = session.phase != SessionPhase.SAVING, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("停止并保存")
                }
            } else {
                session.message?.let { Text(it, color = if (session.phase == SessionPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("start-recording")) { Text("开始录音") }
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
    session: SessionState,
    playback: PlaybackState,
    showBack: Boolean,
    onBack: () -> Unit,
    onPlay: (Recording) -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    onSettings: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("recording-detail"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showBack) TextButton(onClick = onBack) { Text("返回录音库") }
        if (recording == null) {
            Text("录音正在加载…")
            return@Column
        }
        Text(recordingTitle(recording), style = MaterialTheme.typography.headlineMedium)
        Text(statusLabel(recording.status), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.displaySmall)
        recording.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (session.active) {
            Text("正在录音，停止保存后可播放。")
            Button(onClick = onStop, enabled = session.phase != SessionPhase.SAVING) { Text("停止并保存") }
        } else if (recording.fileName != null) {
            val thisPlayback = playback.id == recording.id
            val position = if (thisPlayback) playback.positionMs else 0
            var seeking by remember(recording.id) { mutableFloatStateOf(-1f) }
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
            Text("${formatDuration(position)} / ${formatDuration(recording.durationMs)}")
            Button(onClick = { onPlay(recording) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (thisPlayback && playback.playing) "暂停播放" else "播放录音")
            }
            if (thisPlayback) playback.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
        Text("原始音频", style = MaterialTheme.typography.titleMedium)
        Text("M4A · AAC · 单声道\n保存在应用私有存储中", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onSettings != null) {
            HorizontalDivider()
            Text("转写与整理", style = MaterialTheme.typography.titleMedium)
            Text("原始录音已保留。可先配置转写、标题和总结使用的服务；AI 任务将在后续版本接通。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSettings) { Text("配置 AI 服务") }
        }
    }
}

fun recordingTitle(recording: Recording): String = runCatching {
    DateTimeFormatter.ofPattern("MM月dd日 HH:mm:ss").withZone(ZoneId.of(recording.zone)).format(Instant.ofEpochMilli(recording.startedAt))
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
