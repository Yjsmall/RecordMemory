package dev.local.record.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.local.record.agent.BuiltInSkill
import dev.local.record.ai.toolConfigurationFingerprint
import dev.local.record.settings.AiCapability

enum class AgentSettingsPage(val label: String) { IDENTITY("身份"), SKILLS("技能"), MEMORY("记忆方式") }

@Composable
fun AgentSettingsContent(state: SettingsUiState, model: SettingsViewModel, onCapability: (AiCapability) -> Unit) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val back = { if (state.soulDirty) discard = true else model.closeAgentPage() }
    BackHandler(enabled = !state.busy, onBack = back)
    TextButton(onClick = back, enabled = !state.busy) { Text("返回助手设置") }
    state.agentCatalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (state.agentPage) {
        AgentSettingsPage.IDENTITY -> IdentitySettings(state, model)
        AgentSettingsPage.SKILLS -> SkillSettings(state, model)
        AgentSettingsPage.MEMORY -> MemoryModeSettings(state, model, onCapability)
        null -> Unit
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("放弃身份修改？") },
            text = { Text("当前输入尚未保存。") },
            confirmButton = {
                TextButton(onClick = {
                    discard = false
                    model.closeAgentPage()
                }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun IdentitySettings(state: SettingsUiState, model: SettingsViewModel) {
    Text("设置助手的交流方式。当前对话的临时要求仍会优先采用。", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.soulDraft.orEmpty(),
        onValueChange = model::updateSoul,
        label = { Text("助手身份与交流风格") },
        minLines = 8,
        enabled = !state.busy && state.defaultSoul.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("agent-soul-editor")
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = model::restoreDefaultSoul, enabled = !state.busy && state.defaultSoul.isNotBlank()) { Text("恢复内置默认") }
        if (state.configuration?.binding(AiCapability.ANSWER)?.prompt != AiCapability.ANSWER.defaultPrompt) {
            TextButton(onClick = model::usePreviousAnswerPrompt, enabled = !state.busy) { Text("使用原助手提示词作草稿") }
        }
    }
    Text("身份仅加密保存在本机，普通配置导出不包含此内容。保存后在下一轮对话生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.soulDraft.orEmpty().length > 6_000) {
        Text(
            "身份内容较长，结合技能与对话可能超过本轮上下文容量。若回答提示内容过长，请缩短身份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("agent-soul-budget-warning")
        )
    }
    Button(onClick = { model.saveSoul() }, enabled = !state.busy && state.soulDirty, modifier = Modifier.testTag("save-agent-soul")) { Text("保存身份") }
}

@Composable
private fun SkillSettings(state: SettingsUiState, model: SettingsViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    Text("启用后可按任务选用，或在对话中输入 /技能名称。关闭会停止正在使用该技能的回答。", style = MaterialTheme.typography.bodyMedium)
    SectionLabel("可用技能", "${state.agentSkills.count { it.enabled(state.agentPreferences) }} 项已启用")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val selected = state.agentSkills.firstOrNull { it.id == selectedId }
        val list: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.agentSkills.forEach { skill ->
                    Surface(onClick = { selectedId = if (selectedId == skill.id) null else skill.id }, shape = MaterialTheme.shapes.medium, color = if (selectedId == skill.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().testTag("agent-skill-${skill.id}")) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                Text(skill.description.substringBefore('。'), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (selectedId == skill.id) "收起详情" else "查看用法与详情", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Switch(
                                checked = skill.enabled(state.agentPreferences),
                                onCheckedChange = { model.setSkillEnabled(skill.id, it) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("skill-enabled-${skill.id}")
                            )
                        }
                    }
                }
            }
        }
        if (maxWidth >= 600.dp && selected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { list() }
                Column(Modifier.weight(1f)) { SkillDetail(selected) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                list()
                selected?.let { SkillDetail(it) }
            }
        }
    }
    SectionLabel("模型能力")
    ToolCapabilityStatus(state)
}

@Composable
private fun SkillDetail(skill: BuiltInSkill) {
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            Text(skill.description)
            Text("对话输入 /${skill.id} 可明确选用。启用不会自动运行。", style = MaterialTheme.typography.bodySmall)
            Text("需要：${skill.requiredTools.map(::toolLabel).distinct().joinToString("、")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MemoryModeSettings(state: SettingsUiState, model: SettingsViewModel, onCapability: (AiCapability) -> Unit) {
    val config = state.configuration ?: return
    val memory = config.binding(AiCapability.MEMORY)
    val connection = config.connections.firstOrNull { it.id == memory.connectionId }
    val configured = state.agentMemoryConfigured
    SectionLabel("如何整理")
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("自动整理候选", style = MaterialTheme.typography.titleMedium)
                    Text("回答完成后，使用独立记忆模型整理有限对话。默认产生待审核候选。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.agentPreferences.autoLearning, model::setAutoLearning, enabled = !state.busy && (configured || state.agentPreferences.autoLearning), modifier = Modifier.testTag("agent-auto-learning"))
            }
            Text(if (configured) "记忆模型：${connection?.name} · ${memory.model}" else "请先配置记忆提取模型与所需密钥，普通聊天与手动记住仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("开启会发送对话文本并产生模型费用。每天最多 20 次，每条最多 1 次；中断不会自动付费重试。关闭会停止自动整理，历史批次可在检索与学习中单独授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onCapability(AiCapability.MEMORY) }, enabled = !state.busy) { Text("配置记忆模型") }
        }
    }
    SectionLabel("如何保存")
    SettingsGroup {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动保存普通事实", style = MaterialTheme.typography.titleMedium)
                Text("仅保存通过严格规则的完整、直接偏好或普通项目陈述。敏感信息、冲突、替代与不明确内容仍需审核。最近学习中可查看结果和纠正。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("默认关闭。自动保存范围较窄，其他内容会继续作为候选供你审核。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.agentPreferences.autoConfirm, model::setAutoConfirm, enabled = !state.busy && (configured || state.agentPreferences.autoConfirm), modifier = Modifier.testTag("agent-auto-confirm"))
        }
    }
    SectionLabel("主动回忆")
    ToolCapabilityStatus(state)
    val answer = config.binding(AiCapability.ANSWER)
    val answerConnection = config.connections.firstOrNull { it.id == answer.connectionId }
    Text("检查主动回忆会向助手模型发送固定测试文字，可能产生少量费用。", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = model::probeTools, enabled = !state.busy && !state.probingTools && answerConnection?.supports(AiCapability.ANSWER) == true && answer.model.isNotBlank(), modifier = Modifier.testTag("probe-agent-tools")) {
        Text(if (state.probingTools) "正在检查…" else "检查主动回忆能力")
    }
}

@Composable
private fun ToolCapabilityStatus(state: SettingsUiState) {
    val config = state.configuration ?: return
    val binding = config.binding(AiCapability.ANSWER)
    val connection = config.connections.firstOrNull { it.id == binding.connectionId }
    val supported = connection != null && state.agentPreferences.toolChecks["${connection.id}:${binding.model}"] == toolConfigurationFingerprint(connection, binding)
    Text(if (supported) "助手模型已支持主动回忆与按需加载技能。" else "主动回忆尚未通过检查；仍可普通对话，明确选中的技能将随本轮加载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun toolLabel(tool: String): String = when (tool) {
    "get_personal_profile" -> "当前画像"
    "search_memories" -> "记忆查询"
    "get_memory_sources" -> "来源核对"
    "search_local_sources" -> "本机文本查询"
    "read_skill_resource", "load_skill" -> "技能资料"
    else -> "不可用工具"
}
