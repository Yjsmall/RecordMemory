package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.LearningFeedback
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.TurnStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Query and selection belong to the ViewModel; hiding filters never clears them. */
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
    val query = state.historyQuery
    var filters by rememberSaveable { mutableStateOf(query.fromDate.isNotBlank() || query.throughDate.isNotBlank() || query.project.isNotBlank()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        keyboard?.hide()
        onSearch()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("history-learning-screen")) {
            PageHeader("检索与学习", onBack)
            PrimaryScrollableTabRow(selectedTabIndex = if (learning) 1 else 0, containerColor = MaterialTheme.colorScheme.surface, edgePadding = 20.dp, divider = {}) {
                Tab(!learning, { learning = false }, text = { Text("历史检索") }, modifier = Modifier.testTag("history-tab"))
                Tab(learning, { learning = true }, text = { Text("最近学习") }, modifier = Modifier.testTag("learning-tab"))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history-results"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.problem?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
                state.historyMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
                if (learning) {
                    item { Text("最近 50 项记忆变更与整理任务，可查看来源、审核或纠正。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.learningFeedback.isEmpty()) item { EmptyState(RecordIcons.Memory, "还没有学习记录", "整理对话或录音后，在这里查看进展与结果") }
                    items(state.learningFeedback, key = { it.id }) { change -> FeedbackCard(change, state, onMemories, onCancelTask, onOpenSource) }
                } else {
                    item {
                        SettingsGroup {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("查找聊过、录下的内容", style = MaterialTheme.typography.titleMedium)
                                Text("检索在本机完成，不上传原文", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(
                                    query.query, { onQuery(query.copy(query = it.take(200))) }, label = { Text("关键词") },
                                    leadingIcon = { Icon(RecordIcons.Search, null) }, singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { if (state.ready && !state.busy) search() }),
                                    modifier = Modifier.fillMaxWidth().testTag("history-keyword")
                                )
                                val filterCount = listOf(query.fromDate, query.throughDate, query.project).count { it.isNotBlank() }
                                Disclosure(if (filterCount == 0) "日期与项目筛选" else "日期与项目筛选 · $filterCount 项", filters, { filters = !filters }, Modifier.testTag("history-filters"))
                                if (filters) {
                                    HistoryDateFields(query, onQuery)
                                    OutlinedTextField(query.project, { onQuery(query.copy(project = it.take(80))) }, label = { Text("项目") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("history-project"))
                                    Text("日期格式：年-月-日，包含起止当天。项目按关联范围或原文名称筛选。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = search, enabled = state.ready && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-history")) { Text(if (state.busy) "处理中…" else "检索本机原文") }
                            }
                        }
                    }
                    if (state.historySearched) {
                        item { SectionLabel("检索结果", "${state.historyHits.size} 条 · 最多 10 条") }
                        if (state.historyHits.isEmpty()) item { EmptyState(RecordIcons.Search, "没有找到相关原文", "试试其他关键词，或放宽日期与项目筛选") }
                    }
                    items(state.historyHits, key = { it.contentId }) { hit ->
                        Surface(shape = MaterialTheme.shapes.medium, color = if (hit.ownerId in state.selectedHistory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().testTag("history-hit-${hit.contentId}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatusPill(historyOriginLabel(hit.origin))
                                    Text(sourceDate(hit.observedAt, hit.zoneId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                                }
                                if (hit.project.isNotBlank()) Text("项目：${hit.project}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(hit.text, style = MaterialTheme.typography.bodyLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onOpenSource(hit.contentId) }, enabled = !state.busy) { Text("打开来源") }
                                    val selectable = hit.origin == "USER_MESSAGE" && state.allTurns.any { it.id == hit.ownerId && it.status == TurnStatus.ANSWERED }
                                    if (selectable) {
                                        val selected = hit.ownerId in state.selectedHistory
                                        Row(
                                            Modifier.heightIn(min = 48.dp).toggleable(selected, enabled = !state.busy && (state.selectedHistory.size < 10 || selected), role = Role.Checkbox, onValueChange = { onSelect(hit, it) }).testTag("select-history-${hit.ownerId}").padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(selected, onCheckedChange = null)
                                            Text("整理这条", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.historyHits.isNotEmpty()) item { Text("可勾选已完成的聊天进行记忆整理。录音记忆请在来源详情中提取。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (!learning && state.selectedHistory.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.memoryConfigured) "整理会上传所选聊天，下一步确认服务与费用" else "请先配置记忆提取模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { authorize = true }, enabled = state.memoryConfigured && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-history")) { Text("整理所选 ${state.selectedHistory.size} 条") }
                    }
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
private fun HistoryDateFields(query: HistoryQuery, onQuery: (HistoryQuery) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val from: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(query.fromDate, { onQuery(query.copy(fromDate = it.take(10))) }, label = { Text("开始日期") }, placeholder = { Text("年-月-日") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = modifier.testTag("history-from"))
        }
        val through: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(query.throughDate, { onQuery(query.copy(throughDate = it.take(10))) }, label = { Text("结束日期") }, placeholder = { Text("年-月-日") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = modifier.testTag("history-through"))
        }
        if (maxWidth >= 400.dp && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                from(Modifier.weight(1f))
                through(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                from(Modifier.fillMaxWidth())
                through(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FeedbackCard(change: LearningFeedback, state: AssistantUiState, onMemories: () -> Unit, onCancelTask: (String) -> Unit, onOpenSource: (String) -> Unit) {
    var details by rememberSaveable(change.id) { mutableStateOf(false) }
    SettingsGroup {
        Column(Modifier.padding(16.dp).testTag("learning-change-${change.id}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(change.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 3.dp))
                StatusPill(change.status)
            }
            Text(sourceDate(change.occurredAt, ZoneId.systemDefault().id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (change.previousText.isNotBlank()) {
                Text("原来", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(change.previousText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (change.text.isNotBlank()) {
                if (change.previousText.isNotBlank()) {
                    val label = when (change.status) {
                        "已保存" -> "现在"
                        "待审核", "需要澄清" -> "建议改为"
                        else -> "变更内容"
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(change.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (change.failure.isNotBlank()) Text(change.failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (change.source.isNotBlank() || change.evidence.isNotBlank() || change.model.isNotBlank()) {
                Disclosure("来源与依据", details, { details = !details })
                if (details) {
                    if (change.source.isNotBlank()) Text("来源：${change.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (change.evidence.isNotBlank()) Text("依据：${change.evidence}", style = MaterialTheme.typography.bodyMedium)
                    if (change.model.isNotBlank()) Text(change.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                change.sourceContentId?.let { id -> TextButton(onClick = { onOpenSource(id) }, enabled = !state.busy) { Text("打开来源") } }
                if (change.memoryId != null && state.memories.any { it.id == change.memoryId && it.visible }) TextButton(onClick = onMemories) { Text("审核或纠正") }
                change.taskId?.let { id ->
                    if (state.memoryTasks.any { it.id == id && it.status in setOf(MemoryPlanningStatus.REQUESTED, MemoryPlanningStatus.RUNNING) }) TextButton(onClick = { onCancelTask(id) }, enabled = !state.busy) { Text("取消整理") }
                }
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
