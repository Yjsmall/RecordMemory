package dev.local.record.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus

/** Parent owns safeDrawing (including IME); the composer stays outside the scrolling messages. */
@Composable
internal fun AssistantScreen(
    state: AssistantUiState,
    session: SessionState,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: () -> Unit,
    onRemember: (String, String, MemoryKind) -> Unit,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    onMemories: () -> Unit,
    onConfigure: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var remembering by remember { mutableStateOf<AssistantTurn?>(null) }
    val list = rememberLazyListState()
    val turns = state.turns
    LaunchedEffect(state.conversationId, turns.size, turns.lastOrNull()?.status) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.lastIndex)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("assistant-screen"), contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 420.dp
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(RecordIcons.Back, "返回") }
                Column(Modifier.weight(1f)) {
                    Text("私人助手", style = MaterialTheme.typography.titleLarge)
                    if (!compact && state.provider.isNotBlank()) Text(state.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onMemories, modifier = Modifier.testTag("assistant-memories")) { Icon(RecordIcons.Memory, "我的记忆") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(RecordIcons.More, "对话操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("新对话") }, enabled = !state.busy, onClick = {
                            menu = false
                            onNew()
                        })
                        DropdownMenuItem(text = { Text("历史对话") }, onClick = {
                            menu = false
                            history = true
                        })
                        DropdownMenuItem(text = { Text("对话模型") }, onClick = {
                            menu = false
                            onConfigure()
                        })
                        DropdownMenuItem(text = { Text("删除当前对话") }, enabled = state.conversationId != null && !state.busy, onClick = {
                            menu = false
                            deleting = true
                        })
                    }
                }
            }
            if (session.active) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${sessionLabel(session.phase)} · ${formatDuration(session.durationMs)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onPause) { Text(if (session.phase == dev.local.record.audio.SessionPhase.PAUSED) "继续" else "暂停") }
                    TextButton(onClick = onStop, enabled = session.phase != dev.local.record.audio.SessionPhase.SAVING) { Text("停止保存") }
                }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("assistant-messages"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (turns.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconBadge(RecordIcons.Spark, size = 64)
                            Text("慢慢认识你", style = MaterialTheme.typography.headlineMedium)
                            Text("偏好、计划，还有今天的想法", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.configured) {
                                TextButton(onClick = { onDraft("我想先和你聊聊我自己") }) { Text("聊聊我自己") }
                                TextButton(onClick = { onDraft("你目前对我有哪些了解？") }) { Text("你对我有哪些了解？") }
                            } else {
                                Button(onClick = onConfigure, modifier = Modifier.testTag("configure-assistant")) { Text("选择对话模型") }
                            }
                        }
                    }
                }
                items(turns, key = { it.id }) { turn ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(Alignment.End).widthIn(max = 600.dp)) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                SelectionContainer { Text(turn.userText, style = MaterialTheme.typography.bodyLarge) }
                                TextButton(onClick = { remembering = turn }, modifier = Modifier.align(Alignment.End).testTag("remember-turn-${turn.id}")) { Text("记住…", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        when (turn.status) {
                            TurnStatus.REQUESTED, TurnStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在思考", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { onCancel(turn.id) }) { Text("停止") }
                            }
                            TurnStatus.ANSWERED -> {
                                AssistantAnswer(turn.reply)
                                state.memories.filter { it.sourceTurnId == turn.id && it.visible }.forEach { memory ->
                                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(if (memory.status == MemoryStatus.CONFIRMED) "已记住 · ${memory.type.label}" else "可以记住 · ${memory.type.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(memory.text, style = MaterialTheme.typography.bodyMedium)
                                            if (memory.status == MemoryStatus.CANDIDATE) {
                                                Row(Modifier.align(Alignment.End)) {
                                                    TextButton(onClick = { onForget(memory.id) }) { Text("忽略") }
                                                    FilledTonalButton(onClick = { onConfirm(memory.id) }, modifier = Modifier.testTag("assistant-confirm-${memory.id}")) { Text("确认记忆") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            TurnStatus.FAILED, TurnStatus.CANCELLED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(turnFailureLabel(turn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onRetry(turn.id) }, enabled = state.activeTurn == null && state.configured) { Text("重试") }
                            }
                            TurnStatus.DELETED -> Unit
                        }
                    }
                }
            }
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    state.draft,
                    onDraft,
                    modifier = Modifier.weight(1f).heightIn(max = if (compact) 88.dp else 144.dp).testTag("assistant-input"),
                    placeholder = { Text("想聊些什么？") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = if (compact) 2 else 4,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
                )
                FilledIconButton(onClick = onSend, enabled = state.ready && state.configured && !state.busy && state.activeTurn == null && state.draft.isNotBlank(), modifier = Modifier.size(52.dp).testTag("assistant-send")) { Icon(RecordIcons.Send, "发送") }
            }
            if (!compact) {
                Text(
                    if (state.configured) "发送至所选服务 · 可使用 ${state.memories.count { it.status == MemoryStatus.CONFIRMED }} 条已确认记忆" else "在设置中配置私人助手模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                )
            }
        }
    }
    remembering?.let { turn ->
        RememberDialog(turn.userText, { remembering = null }) { text, kind ->
            onRemember(turn.id, text, kind)
            remembering = null
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除这段对话？") },
            text = { Text("对话正文和由它产生的记忆将被清理。其他来源的记忆保留。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
        )
    }
    if (history) {
        AlertDialog(onDismissRequest = { history = false }, title = { Text("历史对话") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (state.conversations.isEmpty()) Text("还没有对话")
                state.conversations.forEach { conversation ->
                    val title = state.allTurns.firstOrNull { it.conversationId == conversation.id }?.userText ?: "新对话"
                    TextButton(onClick = {
                        onSelect(conversation.id)
                        history = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
    }
}

@Composable
private fun AssistantAnswer(reply: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(RecordIcons.Spark, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("随声助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        SelectionContainer { Text(reply, style = MaterialTheme.typography.bodyLarge) }
        TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("助手回复", reply)) }) { Text("复制", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun RememberDialog(initial: String, onDismiss: () -> Unit, onSave: (String, MemoryKind) -> Unit) {
    var text by remember { mutableStateOf(initial.take(500)) }
    var kind by remember { mutableStateOf(MemoryKind.PREFERENCE) }
    var kinds by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("记住这件事") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                TextButton(onClick = { kinds = true }) { Text("${kind.label} ▾") }
                DropdownMenu(kinds, { kinds = false }) {
                    MemoryKind.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = {
                            kind = type
                            kinds = false
                        })
                    }
                }
            }
            OutlinedTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("remember-input"), minLines = 3, shape = RoundedCornerShape(16.dp))
        }
    }, confirmButton = { Button(onClick = { onSave(text, kind) }, enabled = text.isNotBlank()) { Text("保存记忆") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun turnFailureLabel(turn: AssistantTurn): String = when {
    turn.status == TurnStatus.CANCELLED -> "已停止回复"
    turn.failure == "INTERRUPTED" -> "上次回复中断，可手动重试"
    turn.failure == "CONTEXT_CHANGED" -> "记忆已变化，请重试"
    turn.failure == "NETWORK" -> "连接失败，请稍后重试"
    turn.failure == "CONFIG_OR_FORMAT" -> "请检查对话模型与回复格式"
    else -> "回复未完成，请检查服务后重试"
}

@Preview(name = "助手外屏", widthDp = 380, heightDp = 800)
@Preview(name = "助手内屏", widthDp = 900, heightDp = 700)
@Composable
private fun AssistantPreview() {
    RecordTheme { AssistantScreen(AssistantUiState(ready = true), SessionState(), {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}) }
}
