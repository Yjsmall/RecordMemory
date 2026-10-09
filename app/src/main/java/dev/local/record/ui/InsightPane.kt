package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.record.domain.AiJob
import dev.local.record.domain.JobStatus
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.RecordingText
import dev.local.record.settings.AiCapability

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
    val transcriptJob = jobs.lastOrNull { it.capability == AiCapability.ASR.name }
    SectionLabel("转写")
    Text(jobLabel(transcriptJob, text?.transcript.isNullOrBlank()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    text?.transcript?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onTranscribe(recordingId) }, enabled = transcriptJob?.status != JobStatus.RUNNING) {
            Text(if (text?.transcript.isNullOrBlank()) "转写" else "重新转写")
        }
    }
    Editor("编辑转写", text?.transcript.orEmpty()) { onSaveTranscript(recordingId, it) }
    SectionLabel("标题")
    Text(text?.title ?: "还没有标题", style = MaterialTheme.typography.titleMedium)
    text?.titleSuggestion?.let {
        Text("建议：$it", color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { onAcceptTitle(recordingId) }) { Text("采用建议") }
    }
    if (text?.stale == true) Text("原文已修改，标题和总结可能过时。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { onGenerate(recordingId, AiCapability.TITLE) }, enabled = text?.transcript != null) { Text("生成标题") }
    Editor("编辑标题", text?.title.orEmpty()) { onSaveTitle(recordingId, it) }
    SectionLabel("总结")
    Text(text?.summary ?: "还没有总结", style = MaterialTheme.typography.bodyMedium)
    text?.summarySuggestion?.let {
        Text("建议：$it", color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { onAcceptSummary(recordingId) }) { Text("采用建议") }
    }
    Text(jobLabel(jobs.lastOrNull { it.capability == AiCapability.SUMMARY.name }, text?.summary.isNullOrBlank()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { onGenerate(recordingId, AiCapability.SUMMARY) }, enabled = text?.transcript != null) { Text("生成总结") }
    Editor("编辑总结", text?.summary.orEmpty()) { onSaveSummary(recordingId, it) }
    SectionLabel("这条录音的记忆")
    OutlinedButton(onClick = { onGenerate(recordingId, AiCapability.MEMORY) }, enabled = text?.transcript != null) { Text("提取记忆") }
    val related = memories.filter { it.sourceRecordingId == recordingId && it.visible }
    if (related.isEmpty()) Text("还没有记忆候选。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    related.forEach { memory ->
        MemoryCard(memory, memories.filter { it.visible && it.id != memory.id }, onConfirm, onForget, onDisable, onCorrect, onMerge)
    }
}

@Composable
internal fun MemoryLibrary(
    memories: List<MemoryItem>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    onDisable: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onMerge: (String, String) -> Unit
) {
    val visible = memories.filter { it.visible }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("记忆", style = MaterialTheme.typography.headlineMedium)
        Text("候选需要确认后才算目前认可的内容。忘记或禁用后，同一录音不会自动重新提出相同内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (visible.isEmpty()) Text("还没有记忆。完成转写后可以提取人物、项目、偏好、约定和待办。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        visible.forEach { memory ->
            MemoryCard(memory, visible.filter { it.id != memory.id }, onConfirm, onForget, onDisable, onCorrect, onMerge)
        }
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryItem,
    others: List<MemoryItem>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    onDisable: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onMerge: (String, String) -> Unit
) {
    var merging by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${memory.type.label} · ${if (memory.status == MemoryStatus.CONFIRMED) "已确认" else "候选"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(memory.text, style = MaterialTheme.typography.bodyLarge)
            if (memory.evidence.isNotBlank()) Text("依据：${memory.evidence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (memory.status == MemoryStatus.CANDIDATE) TextButton(onClick = { onConfirm(memory.id) }) { Text("确认") }
                TextButton(onClick = { onDisable(memory.id) }) { Text("禁用") }
                TextButton(onClick = { onForget(memory.id) }) { Text("忘记") }
                if (others.isNotEmpty()) TextButton(onClick = { merging = true }) { Text("合并") }
            }
            Editor("编辑", memory.text) { onCorrect(memory.id, it) }
        }
    }
    if (merging) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("合并到") },
            text = {
                Column {
                    others.forEach { target ->
                        TextButton(onClick = {
                            merging = false
                            onMerge(memory.id, target.id)
                        }) { Text("${target.type.label}：${target.text}", maxLines = 2) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun Editor(label: String, initial: String, onSave: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起" else label) }
    if (open) {
        OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存") }
    }
}

private fun jobLabel(job: AiJob?, empty: Boolean) = when (job?.status) {
    JobStatus.REQUESTED, JobStatus.RUNNING -> "正在处理"
    JobStatus.FAILED -> "失败：${job.error ?: "请重试"}"
    JobStatus.STALE -> "结果已过期，未覆盖当前内容"
    JobStatus.SUCCEEDED -> if (empty) "已完成，但没有可用文本" else "已完成"
    JobStatus.CANCELLED -> "已取消"
    null -> if (empty) "尚未处理" else "已有内容"
}
