package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.LearningFeedback
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.TurnStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Query inputs live in the Activity ViewModel; saved state only retains the selected page. */
@Composable
internal fun HistoryLearningScreen(
    state: AssistantUiState,
    onBack: () -> Unit,
    onQuery: (HistoryQuery) -> Unit,
    onSearch: () -> Unit,
    onSelect: (HistoryHit, Boolean) -> Unit,
    onPlan: () -> Unit,
    onOpenSource: (String) -> Unit,
    onMemories: () -> Unit,
    onCancelTask: (String) -> Unit
) {
    var learning by rememberSaveable { mutableStateOf(false) }
    var authorize by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("history-learning-screen")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(RecordIcons.Back, "返回") }
            Text(if (learning) "最近学习" else "历史与录音检索", style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!learning, { learning = false }, label = { Text("检索") }, modifier = Modifier.testTag("history-tab"))
            FilterChip(learning, { learning = true }, label = { Text("最近学习") }, modifier = Modifier.testTag("learning-tab"))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history-results"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.problem?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error) } }
            state.historyMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            if (learning) {
                item { Text("展示最近 50 项变更及当前审核状态。已忘记、删除的正文不会在这里恢复。", style = MaterialTheme.typography.bodySmall) }
                if (state.learningFeedback.isEmpty()) item { Text("还没有记忆变更或整理任务") }
                items(state.learningFeedback, key = { it.id }) { change ->
                    FeedbackCard(change, state, onMemories, onCancelTask, onOpenSource)
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val query = state.historyQuery
                        OutlinedTextField(query.query, { onQuery(query.copy(query = it.take(200))) }, label = { Text("关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("history-keyword"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(query.fromDate, { onQuery(query.copy(fromDate = it.take(10))) }, label = { Text("开始日期") }, placeholder = { Text("2026-10-01") }, singleLine = true, modifier = Modifier.weight(1f).testTag("history-from"))
                            OutlinedTextField(query.throughDate, { onQuery(query.copy(throughDate = it.take(10))) }, label = { Text("结束日期") }, placeholder = { Text("2026-10-10") }, singleLine = true, modifier = Modifier.weight(1f).testTag("history-through"))
                        }
                        OutlinedTextField(query.project, { onQuery(query.copy(project = it.take(80))) }, label = { Text("项目") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("history-project"))
                        Text("日期格式为年-月-日，包含起止当天。项目按关联范围或原文中的项目名筛选；最多显示 10 条，可缩小条件。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onSearch, enabled = state.ready && !state.busy, modifier = Modifier.testTag("search-history")) { Text(if (state.busy) "处理中…" else "检索本机原文") }
                        if (state.historySearched && state.historyHits.isEmpty()) Text("没有符合条件的可检索原文")
                    }
                }
                items(state.historyHits, key = { it.contentId }) { hit ->
                    Card(Modifier.fillMaxWidth().testTag("history-hit-${hit.contentId}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${historyOriginLabel(hit.origin)} · ${sourceDate(hit.observedAt, hit.zoneId)}", style = MaterialTheme.typography.labelMedium)
                            if (hit.project.isNotBlank()) Text("项目：${hit.project}", style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpenSource(hit.contentId) }, enabled = !state.busy) { Text("打开来源") }
                                val selectable = hit.origin == "USER_MESSAGE" && state.allTurns.any { it.id == hit.ownerId && it.status == TurnStatus.ANSWERED }
                                if (selectable) {
                                    Checkbox(hit.ownerId in state.selectedHistory, { onSelect(hit, it) }, enabled = !state.busy && (state.selectedHistory.size < 10 || hit.ownerId in state.selectedHistory), modifier = Modifier.testTag("select-history-${hit.ownerId}"))
                                    Text("整理这条", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                item {
                    Text("历史整理发送你选中的已完成聊天原文及相关已确认记忆。录音可在来源详情中使用记忆提取。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { authorize = true }, enabled = state.memoryConfigured && !state.busy && state.selectedHistory.isNotEmpty(), modifier = Modifier.testTag("plan-history")) { Text("整理所选 ${state.selectedHistory.size} 条") }
                    if (!state.memoryConfigured) Text("请先配置记忆提取模型", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (authorize) {
        AlertDialog(
            onDismissRequest = { authorize = false },
            title = { Text("授权整理所选历史？") },
            text = { Text("将发送所选 ${state.selectedHistory.size} 条聊天原文及相关记忆至 ${state.memoryProvider}。最多 ${state.selectedHistory.size} 次调用，会产生模型费用；实际费用由服务计价。已有整理记录的来源不重复调用，中断后不自动重试。可在最近学习中取消排队或运行任务；失败后可打开来源明确重试。") },
            confirmButton = {
                TextButton(onClick = {
                    authorize = false
                    onPlan()
                }, enabled = !state.busy && state.selectedHistory.isNotEmpty(), modifier = Modifier.testTag("authorize-history")) { Text("授权并排队") }
            },
            dismissButton = { TextButton(onClick = { authorize = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun FeedbackCard(change: LearningFeedback, state: AssistantUiState, onMemories: () -> Unit, onCancelTask: (String) -> Unit, onOpenSource: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("learning-change-${change.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${change.label} · ${change.status}", style = MaterialTheme.typography.titleSmall)
            Text(sourceDate(change.occurredAt, ZoneId.systemDefault().id), style = MaterialTheme.typography.labelSmall)
            if (change.previousText.isNotBlank()) Text("原来：${change.previousText}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (change.text.isNotBlank()) Text(if (change.previousText.isNotBlank()) "现在：${change.text}" else change.text)
            if (change.source.isNotBlank()) Text("来源：${change.source}", style = MaterialTheme.typography.bodySmall)
            if (change.evidence.isNotBlank()) Text("依据：${change.evidence}", style = MaterialTheme.typography.bodySmall)
            change.sourceContentId?.let { id -> TextButton(onClick = { onOpenSource(id) }, enabled = !state.busy) { Text("打开来源") } }
            if (change.model.isNotBlank()) Text(change.model, style = MaterialTheme.typography.bodySmall)
            if (change.failure.isNotBlank()) Text(change.failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (change.memoryId != null && state.memories.any { it.id == change.memoryId && it.visible }) TextButton(onClick = onMemories) { Text("审核或纠正") }
            change.taskId?.let { id ->
                if (state.memoryTasks.any { it.id == id && it.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING) }) TextButton(onClick = { onCancelTask(id) }, enabled = !state.busy) { Text("取消整理") }
            }
        }
    }
}

private fun historyOriginLabel(origin: String) = when (origin) {
    "USER_MESSAGE" -> "聊天原文"
    "ASSISTANT_REPLY" -> "助手回复"
    "MACHINE_TRANSCRIPT" -> "机器转写"
    "USER_TRANSCRIPT" -> "修订转写"
    else -> "本机来源"
}

private fun sourceDate(at: Long, zone: String): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)))
