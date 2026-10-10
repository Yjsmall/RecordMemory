package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.record.domain.AiJob
import dev.local.record.domain.JobStatus
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.RecordingText
import dev.local.record.settings.AiCapability

/** Read-first sections; generation, edits and status stay beside the content they affect. */
@Composable
internal fun InsightSection(
    recordingId: String,
    text: RecordingText?,
    jobs: List<AiJob>,
    memories: List<MemoryItem>,
    onTranscribe: (String) -> Unit,
    onGenerate: (String, AiCapability) -> Unit,
    onSaveTranscript: (String, String) -> Unit,
    onSaveTitle: (String, String) -> Unit,
    onSaveSummary: (String, String) -> Unit,
    onAcceptTitle: (String) -> Unit,
    onAcceptSummary: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    onDisable: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onMerge: (String, String) -> Unit
) {
    var section by rememberSaveable(recordingId) { mutableStateOf(0) }
    val transcriptReady = !text?.transcript.isNullOrBlank()
    val related = memories.filter { it.sourceRecordingId == recordingId && it.visible }
    val titles = listOf("总结", "原文", "记忆")
    PrimaryScrollableTabRow(selectedTabIndex = section, containerColor = MaterialTheme.colorScheme.surface, edgePadding = 0.dp, divider = {}) {
        titles.forEachIndexed { index, title ->
            Tab(selected = section == index, onClick = { section = index }, text = { Text(title) }, modifier = Modifier.testTag("insight-tab-$index"))
        }
    }
    when (section) {
        0 -> {
            InsightCard(
                "标题", RecordIcons.Spark, text?.title, latestJob(jobs, AiCapability.TITLE), "生成标题", transcriptReady,
                { onGenerate(recordingId, AiCapability.TITLE) }, "title", { Editor("编辑标题", text?.title.orEmpty(), "title-editor") { onSaveTitle(recordingId, it) } }
            )
            text?.titleSuggestion?.let { Suggestion(it) { onAcceptTitle(recordingId) } }
            InsightCard(
                "总结", RecordIcons.Text, text?.summary, latestJob(jobs, AiCapability.SUMMARY), "生成总结", transcriptReady,
                { onGenerate(recordingId, AiCapability.SUMMARY) }, "summary", { Editor("编辑总结", text?.summary.orEmpty(), "summary-editor") { onSaveSummary(recordingId, it) } }
            )
            text?.summarySuggestion?.let { Suggestion(it) { onAcceptSummary(recordingId) } }
            if (text?.stale == true) Text("原文已修改，整理结果待更新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!transcriptReady) TextButton(onClick = { section = 1 }) { Text("先转写录音") }
        }
        1 -> InsightCard(
            "原文", RecordIcons.Wave, text?.transcript, latestJob(jobs, AiCapability.ASR), if (transcriptReady) "重新转写" else "开始转写", true,
            { onTranscribe(recordingId) }, "transcript", { Editor("编辑转写", text?.transcript.orEmpty(), "transcript-editor") { onSaveTranscript(recordingId, it) } }
        )
        2 -> {
            val job = latestJob(jobs, AiCapability.MEMORY)
            SectionLabel("这段录音的记忆", "${related.size} 条")
            if (related.isEmpty()) EmptyContent(RecordIcons.Memory, "还没有记忆", if (transcriptReady) "从原文中提取值得记住的内容" else "完成转写后即可提取")
            JobFeedback(job)
            FilledTonalButton(onClick = { onGenerate(recordingId, AiCapability.MEMORY) }, enabled = transcriptReady && !job.isBusy(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("generate-memory")) {
                Icon(RecordIcons.Spark, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (job.isBusy()) "正在提取…" else "提取记忆")
            }
            related.forEach { memory ->
                MemoryCard(memory, memories.filter { it.visible && it.id != memory.id }, onConfirm, onForget, onDisable, onCorrect, onMerge)
            }
        }
    }
}

@Composable
private fun InsightCard(title: String, icon: ImageVector, body: String?, job: AiJob?, action: String, enabled: Boolean, onGenerate: () -> Unit, tag: String, editor: @Composable () -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("insight-$tag"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                editor()
            }
            if (!body.isNullOrBlank()) {
                SelectionContainer { Text(body, style = if (tag == "title") MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge) }
            } else if (!job.isBusy()) {
                Text(if (tag == "transcript") "让录音变成文字" else "暂无$title", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            JobFeedback(job)
            FilledTonalButton(onClick = onGenerate, enabled = enabled && !job.isBusy(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("generate-$tag")) {
                Icon(if (tag == "transcript") RecordIcons.Wave else RecordIcons.Spark, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (job.isBusy()) {
                        "正在处理…"
                    } else if (job?.status == JobStatus.FAILED) {
                        "重试"
                    } else {
                        action
                    }
                )
            }
        }
    }
}

@Composable
private fun JobFeedback(job: AiJob?) {
    when (job?.status) {
        JobStatus.REQUESTED, JobStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (job.status == JobStatus.REQUESTED) "等待处理" else "正在处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        JobStatus.FAILED -> Text(job.error ?: "处理失败，请重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        JobStatus.STALE -> Text("原文已更新，请重新生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Unit
    }
}

private fun AiJob?.isBusy() = this?.status == JobStatus.REQUESTED || this?.status == JobStatus.RUNNING

private fun latestJob(jobs: List<AiJob>, capability: AiCapability) = jobs.filter { it.capability == capability.name }.maxWithOrNull(compareBy<AiJob> { it.generation }.thenBy { it.attempt }.thenBy { it.version })

@Composable
private fun Suggestion(body: String, accept: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新建议", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = accept, modifier = Modifier.align(Alignment.End)) { Text("采用建议") }
        }
    }
}

@Composable
internal fun MemoryLibrary(
    memories: List<MemoryItem>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    onDisable: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onOpenSource: ((String) -> Unit)? = null
) {
    var candidates by rememberSaveable { mutableStateOf(false) }
    val visible = memories.filter { it.visible }
    val filtered = visible.filter { !candidates || it.status == MemoryStatus.CANDIDATE }
    LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), modifier = Modifier.fillMaxSize().testTag("memory-library"), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !candidates, onClick = { candidates = false }, label = { Text("全部 ${visible.size}") })
                FilterChip(selected = candidates, onClick = { candidates = true }, label = { Text("待确认 ${visible.count { it.status == MemoryStatus.CANDIDATE }}") })
            }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyContent(RecordIcons.Memory, if (candidates) "没有待确认的记忆" else "留住值得记住的事", if (candidates) "新候选会出现在这里" else "来自录音与对话，由你确认")
            }
        }
        items(filtered, key = { it.id }) { memory -> MemoryCard(memory, visible.filter { it.id != memory.id && it.sourceRecordingId == memory.sourceRecordingId && it.sourceConversationId == memory.sourceConversationId }, onConfirm, onForget, onDisable, onCorrect, onMerge, onOpenSource) }
    }
}

@Composable
private fun EmptyContent(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, size = 64)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemoryCard(memory: MemoryItem, others: List<MemoryItem>, onConfirm: (String) -> Unit, onForget: (String) -> Unit, onDisable: (String) -> Unit, onCorrect: (String, String) -> Unit, onMerge: (String, String) -> Unit, onOpenSource: ((String) -> Unit)? = null) {
    var menu by rememberSaveable(memory.id) { mutableStateOf(false) }
    var merging by rememberSaveable(memory.id) { mutableStateOf(false) }
    var edit by rememberSaveable(memory.id) { mutableStateOf(false) }
    var terminal by rememberSaveable(memory.id) { mutableStateOf<String?>(null) }
    var evidence by rememberSaveable(memory.id) { mutableStateOf(false) }
    SettingsGroup {
        Column(Modifier.padding(20.dp).testTag("memory-${memory.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(memory.type.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(if (memory.status == MemoryStatus.CONFIRMED) "已确认" else "待确认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("memory-menu-${memory.id}")) { Icon(RecordIcons.More, "记忆操作") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = {
                            menu = false
                            edit = true
                        })
                        if (others.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("合并到…") }, onClick = {
                                menu = false
                                merging = true
                            })
                        }
                        DropdownMenuItem(text = { Text("禁用") }, onClick = {
                            menu = false
                            terminal = "禁用"
                        })
                        DropdownMenuItem(text = { Text("忘记", color = MaterialTheme.colorScheme.error) }, onClick = {
                            menu = false
                            terminal = "忘记"
                        })
                    }
                }
            }
            SelectionContainer { Text(memory.text, style = MaterialTheme.typography.bodyLarge) }
            if (evidence && memory.evidence.isNotBlank()) Text(memory.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (memory.evidence.isNotBlank()) TextButton(onClick = { evidence = !evidence }) { Text(if (evidence) "收起依据" else "查看依据") }
                if (onOpenSource != null) TextButton(onClick = { onOpenSource(if (memory.sourceConversationId.isNotBlank()) "chat:${memory.sourceConversationId}" else memory.sourceRecordingId) }) { Text(if (memory.sourceConversationId.isNotBlank()) "原对话" else "原录音") }
                if (memory.status == MemoryStatus.CANDIDATE) FilledTonalButton(onClick = { onConfirm(memory.id) }, modifier = Modifier.testTag("confirm-memory-${memory.id}")) { Text("确认记忆") }
            }
        }
    }
    if (edit) EditDialog("编辑记忆", memory.text, { edit = false }) { onCorrect(memory.id, it) }
    if (terminal != null) {
        AlertDialog(
            onDismissRequest = { terminal = null },
            title = { Text("${terminal}这条记忆？") },
            text = { Text(if (terminal == "忘记") "将移除记忆正文，后续不会作为个人记忆使用。原始录音或对话保留。" else "这条记忆将不再作为个人记忆使用。") },
            confirmButton = {
                TextButton(onClick = {
                    if (terminal == "忘记") onForget(memory.id) else onDisable(memory.id)
                    terminal = null
                }) { Text("确认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { terminal = null }) { Text("取消") } }
        )
    }
    if (merging) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("合并到") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { target ->
                        TextButton(onClick = {
                            merging = false
                            onMerge(memory.id, target.id)
                        }, modifier = Modifier.fillMaxWidth()) { Text(target.text, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun Editor(title: String, initial: String, tag: String, onSave: (String) -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Icon(RecordIcons.Edit, title, Modifier.size(20.dp)) }
    if (open) EditDialog(title, initial, { open = false }, onSave)
}

@Composable
private fun EditDialog(title: String, initial: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val close = { if (value != initial) discard = true else onClose() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("content-editor"), minLines = if (title.contains("标题")) 1 else 4, shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)) },
        confirmButton = {
            Button(onClick = {
                onSave(value.trim())
                onClose()
            }, enabled = value.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } }
    )
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, confirmButton = { TextButton(onClick = onClose) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
