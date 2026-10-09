package dev.local.record.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.record.R
import dev.local.record.audio.SessionPhase
import dev.local.record.audio.SessionState
import dev.local.record.audio.formatDuration
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AppAppearance
import dev.local.record.settings.DOUBAO_ASR
import dev.local.record.settings.OPENAI_COMPATIBLE
import dev.local.record.settings.ProviderPreset
import dev.local.record.settings.RESPONSES
import dev.local.record.settings.supportedProtocols

private val LocalSettingsEnabled = staticCompositionLocalOf { true }

@Composable
fun SettingsHome(state: SettingsUiState, model: SettingsViewModel, onConnection: (String?) -> Unit, onCapability: (AiCapability) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(resolver, it) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.previewImport(resolver, it) }
    }
    val config = state.configuration
    if (config == null) {
        Text(state.loadError ?: "正在读取设置…")
        return
    }
    SectionLabel("AI 服务", "${config.connections.size} 个")
    SettingsGroup {
        config.connections.forEach { connection ->
            SettingsRow(connection.name, if (connection.protocol in supportedProtocols) protocolLabel(connection.protocol) else "协议待配置", RecordIcons.Cloud, "connection-${connection.id}", !state.busy, leadingIcon = { ProviderIcon(ProviderPreset.forConnection(connection)) }) { onConnection(connection.id) }
            GroupDivider()
        }
        SettingsRow("添加连接", if (config.connections.isEmpty()) "DeepSeek、豆包或自定义服务" else null, RecordIcons.Plus, "add-connection", !state.busy) { onConnection(null) }
    }
    SectionLabel("模型与能力")
    SettingsGroup {
        AiCapability.entries.forEachIndexed { index, capability ->
            val binding = config.binding(capability)
            val connection = config.connections.firstOrNull { it.id == binding.connectionId }
            SettingsRow(
                capability.label,
                when {
                    connection == null -> "未配置"
                    connection.protocol !in supportedProtocols -> "协议待配置"
                    binding.model.isBlank() -> "${connection.name} · 选择模型"
                    else -> "${connection.name} · ${binding.model}"
                },
                capabilityIcon(capability),
                "capability-${capability.name}",
                !state.busy
            ) { onCapability(capability) }
            if (index < AiCapability.entries.lastIndex) GroupDivider()
        }
    }
    Text("AI 功能待接入，当前仅保存配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    SectionLabel("外观")
    SettingsGroup {
        Choice("主题", config.appearance.label, AppAppearance.entries.map { it.label }) { index -> model.appearance(AppAppearance.entries[index], config.dynamicColors) }
        GroupDivider()
        ToggleRow("动态配色", "跟随系统壁纸", config.dynamicColors) { model.appearance(config.appearance, it) }
    }
    SectionLabel("配置迁移")
    SettingsGroup {
        SettingsRow("导出配置", "不含密钥与录音", RecordIcons.Export, enabled = !state.busy) { export.launch("随声记-AI配置.json") }
        GroupDivider()
        SettingsRow("导入配置", null, RecordIcons.Import, enabled = !state.busy) { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    }
    state.pendingImport?.let { incoming ->
        val newCount = incoming.connections.count { next -> config.connections.none { it.id == next.id } }
        AlertDialog(
            onDismissRequest = model::cancelImport,
            title = { Text("合并配置") },
            text = { Text("版本 ${incoming.schemaVersion} · ${incoming.connections.size} 个连接\n将新增 $newCount 个连接；同 ID 连接和已绑定能力不覆盖，外观保留。未知协议保持待配置。\n\n新连接的密钥需重新填写。导入不会联网。") },
            confirmButton = { TextButton(onClick = model::confirmImport, enabled = !state.busy) { Text("确认合并") } },
            dismissButton = { TextButton(onClick = model::cancelImport, enabled = !state.busy) { Text("取消") } }
        )
    }
}

@Composable
fun ConnectionEditor(state: SettingsUiState, model: SettingsViewModel, onDeleted: () -> Unit) {
    val draft = state.connectionDraft ?: return
    val connection = draft.connection
    var showKey by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val preset = ProviderPreset.forConnection(connection)
    Choice(
        "服务商",
        preset.label,
        ProviderPreset.entries.map { it.label },
        selectedIcon = { ProviderIcon(preset) },
        optionIcon = { ProviderIcon(ProviderPreset.entries[it]) }
    ) { model.applyPreset(ProviderPreset.entries[it]) }
    if (connection.protocol !in supportedProtocols) {
        Text("导入协议 ${connection.protocol} 尚未支持。此连接不能发起请求。", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { model.updateConnection { it.copy(connection = it.connection.copy(protocol = OPENAI_COMPATIBLE)) } }) { Text("改用 OpenAI 兼容协议") }
    }
    Field("连接名称", connection.name, { value -> model.updateConnection { it.copy(connection = it.connection.copy(name = value)) } }, "connection-name")
    Field("Base URL", connection.baseUrl, { value -> model.updateConnection { it.copy(connection = it.connection.copy(baseUrl = value)) } }, "base-url", keyboard = KeyboardType.Uri)
    if (connection.protocol == DOUBAO_ASR) {
        Field("Resource ID", connection.doubaoResourceId, { value -> model.updateConnection { it.copy(connection = it.connection.copy(doubaoResourceId = value)) } }, "resource-id")
        Text("使用豆包语音服务 API Key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (connection.bearerAuth || connection.protocol == DOUBAO_ASR) {
        OutlinedTextField(
            value = draft.key,
            onValueChange = { value -> model.updateConnection { it.copy(key = value, clearKey = false) } },
            label = { Text("API Key") },
            placeholder = { Text(if (draft.hasStoredKey && !draft.clearKey) "已保存，留空保留" else "输入密钥") },
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("api-key").semantics { contentDescription = "API Key" }
        )
        Text(
            if (draft.clearKey) {
                "保存时将移除密钥"
            } else if (draft.hasStoredKey) {
                "密钥已保存 · 留空保留"
            } else {
                "密钥仅加密保存在本机"
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (draft.hasStoredKey) TextButton(onClick = { model.updateConnection { it.copy(clearKey = !it.clearKey, key = "") } }) { Text(if (draft.clearKey) "保留已存密钥" else "移除已存密钥") }
    }
    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("advanced-settings")) {
        Text(if (advanced) "收起高级设置" else "高级设置")
        Spacer(Modifier.size(6.dp))
        Icon(if (advanced) RecordIcons.Down else RecordIcons.Next, null, Modifier.size(16.dp))
    }
    if (advanced) {
        Choice("接口协议", protocolLabel(connection.protocol), supportedProtocols.map(::protocolLabel)) { index ->
            val protocol = supportedProtocols[index]
            if (protocol == DOUBAO_ASR) {
                model.applyPreset(ProviderPreset.DOUBAO)
            } else {
                model.updateConnection { it.copy(connection = it.connection.copy(protocol = protocol), key = "", clearKey = it.hasStoredKey) }
            }
        }
        if (connection.protocol != DOUBAO_ASR) {
            SettingsGroup {
                ToggleRow("API Key 鉴权", null, connection.bearerAuth) { value -> model.updateConnection { it.copy(connection = it.connection.copy(bearerAuth = value)) } }
                GroupDivider()
                ToggleRow("语音转写接口", "/audio/transcriptions", connection.supportsTranscription) { value -> model.updateConnection { it.copy(connection = it.connection.copy(supportsTranscription = value)) } }
            }
        }
        if (connection.protocol != DOUBAO_ASR) {
            Field("模型列表路径", connection.modelsPath, { value -> model.updateConnection { it.copy(connection = it.connection.copy(modelsPath = value)) } })
            if (connection.protocol == RESPONSES) {
                Field("Responses 路径", connection.responsesPath, { value -> model.updateConnection { it.copy(connection = it.connection.copy(responsesPath = value)) } })
            } else {
                Field("Chat Completions 路径", connection.chatPath, { value -> model.updateConnection { it.copy(connection = it.connection.copy(chatPath = value)) } })
            }
        }
        if (connection.supportsTranscription || connection.protocol == DOUBAO_ASR) Field("转写路径", connection.transcriptionPath, { value -> model.updateConnection { it.copy(connection = it.connection.copy(transcriptionPath = value)) } })
    }
    if (connection.protocol != DOUBAO_ASR) {
        Text("仅获取模型列表，不发送录音", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = model::checkConnection, enabled = !state.checking && !state.busy, modifier = Modifier.fillMaxWidth().testTag("check-connection")) {
            Text(
                if (state.checking) {
                    "正在检查…"
                } else {
                    "检查连接"
                }
            )
        }
        state.check?.let { result ->
            Text(result.message, color = MaterialTheme.colorScheme.primary)
            if (result.modelIds.isNotEmpty()) Text(result.modelIds.take(60).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.configuration?.connections?.any { it.id == connection.id } == true) {
        Surface(
            onClick = { delete = true },
            enabled = !state.busy,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().testTag("delete-connection")
        ) {
            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(RecordIcons.Delete, null, Modifier.size(22.dp))
                Text("删除连接", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("删除连接？") },
            text = { Text("将删除此连接和本机密钥，解除相关能力的绑定。录音保留。") },
            confirmButton = { TextButton(onClick = { model.deleteConnection(onDeleted) }, enabled = !state.busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } }
        )
    }
}

@Composable
fun CapabilityEditor(state: SettingsUiState, model: SettingsViewModel) {
    val binding = state.bindingDraft ?: return
    var confirmTest by rememberSaveable { mutableStateOf(false) }
    val connections = state.configuration?.connections.orEmpty().filter { it.supports(binding.capability) }
    Choice(
        "连接",
        connections.firstOrNull { it.id == binding.connectionId }?.name ?: "未配置",
        listOf("未配置") + connections.map { it.name }
    ) { index ->
        val selected = connections.getOrNull(index - 1)
        model.updateBinding { it.copy(connectionId = selected?.id, model = selected?.modelSuggestions()?.firstOrNull().orEmpty(), reasoningEffort = "") }
    }
    if (connections.isEmpty()) Text("请先返回设置添加连接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    val connection = connections.firstOrNull { it.id == binding.connectionId }
    Field("模型名", binding.model, { value -> model.updateBinding { it.copy(model = value) } }, "model-name", suggestions = connection?.modelSuggestions().orEmpty())
    if (connection?.protocol == RESPONSES) {
        val efforts = listOf("", "none", "low", "medium", "high", "max")
        val labels = listOf("遵循服务默认值", "关闭思考 · none", "低 · low", "中 · medium", "高 · high", "最高 · max")
        Choice("推理强度", labels[efforts.indexOf(binding.reasoningEffort).coerceAtLeast(0)], labels) { index -> model.updateBinding { it.copy(reasoningEffort = efforts[index]) } }
    }
    Field("语言", binding.language, { value -> model.updateBinding { it.copy(language = value) } }, "language")
    SectionLabel("提示词")
    OutlinedTextField(
        value = binding.prompt,
        onValueChange = { value -> model.updateBinding { it.copy(prompt = value) } },
        placeholder = { Text(if (binding.capability == AiCapability.ASR) "补充专有名词或转写要求" else "输入整理要求") },
        minLines = 4,
        shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        textStyle = MaterialTheme.typography.bodyMedium,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("prompt")
    )
    TextButton(onClick = { model.updateBinding { it.copy(prompt = it.capability.defaultPrompt) } }, enabled = !state.busy) { Text("恢复默认提示词") }
    if (binding.capability == AiCapability.ASR) {
        Text(
            if (connection?.protocol == DOUBAO_ASR) {
                "豆包识别待接入 · M4A 上传格式需适配"
            } else {
                "语音转写待接入"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        OutlinedButton(onClick = { confirmTest = true }, enabled = connection != null && binding.model.isNotBlank() && !state.testingModel && !state.busy, modifier = Modifier.fillMaxWidth().testTag("test-text-model")) {
            Text(if (state.testingModel) "正在测试…" else "测试文本模型")
        }
    }
    if (confirmTest) {
        AlertDialog(
            onDismissRequest = { confirmTest = false },
            title = { Text("调用所选模型测试？") },
            text = { Text("将向 ${connection?.name} 发送固定测试文字，调用 ${binding.model}。可能产生少量 token 费用，不会发送录音或个人文本。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTest = false
                    model.testTextModel()
                }) { Text("开始测试") }
            },
            dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("取消") } }
        )
    }
}

private fun protocolLabel(protocol: String): String = when (protocol) {
    RESPONSES -> "Responses"
    OPENAI_COMPATIBLE -> "Chat Completions"
    DOUBAO_ASR -> "豆包语音 · Flash"
    else -> "待配置 · $protocol"
}

/** Parent supplies safeDrawing insets (including IME); one scroll surface keeps fields reachable. */
@Composable
fun SettingsFrame(
    title: String,
    state: SettingsUiState,
    session: SessionState,
    dirty: Boolean,
    onBack: () -> Unit,
    onSave: (() -> Unit)?,
    onPause: () -> Unit,
    onStop: () -> Unit,
    content: @Composable () -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val back = { if (dirty) discard = true else onBack() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.message) { if (state.message != null) scroll.animateScrollTo(0) }
    BackHandler(enabled = !state.busy, onBack = back)
    BackHandler(enabled = state.busy) { /* Atomic save finishes before leaving. */ }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("settings-screen")) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = back, enabled = !state.busy) { Icon(RecordIcons.Back, "返回") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                onSave?.let { save -> Button(onClick = save, enabled = !state.busy) { Text("保存") } }
            }
            if (session.active) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${sessionLabel(session.phase)} · ${formatDuration(session.durationMs)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (session.phase == SessionPhase.RECORDING || session.phase == SessionPhase.PAUSED) {
                                TextButton(onClick = onPause) { Text(if (session.phase == SessionPhase.PAUSED) "继续" else "暂停") }
                            }
                            Button(onClick = onStop, enabled = session.phase != SessionPhase.SAVING) { Text("停止并保存") }
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.busy) CircularProgressIndicator()
                state.message?.let {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("settings-message"))
                    }
                }
                CompositionLocalProvider(LocalSettingsEnabled provides !state.busy) { content() }
            }
        }
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前输入尚未保存。你可以继续编辑，或放弃并返回。") },
            confirmButton = {
                TextButton(onClick = {
                    discard = false
                    onBack()
                }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, icon: ImageVector, tag: String = title, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leadingIcon ?: { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(RecordIcons.Next, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).testTag(tag).padding(vertical = 2.dp)
    )
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, tag: String = label, keyboard: KeyboardType = KeyboardType.Text, suggestions: List<String> = emptyList()) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, onValue, Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label }, enabled = LocalSettingsEnabled.current,
            label = { Text(label) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = RoundedCornerShape(16.dp), colors = fieldColors(), textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = if (suggestions.isEmpty()) {
                null
            } else {
                { IconButton(onClick = { expanded = true }, enabled = LocalSettingsEnabled.current, modifier = Modifier.testTag("$tag-options")) { Icon(RecordIcons.Down, "选择推荐模型") } }
            }
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(text = { Text(suggestion) }, onClick = {
                    expanded = false
                    onValue(suggestion)
                })
            }
        }
    }
}

@Composable
private fun ProviderIcon(preset: ProviderPreset) {
    if (preset == ProviderPreset.DEEPSEEK) {
        Icon(painterResource(R.drawable.ic_deepseek), "DeepSeek 图标", Modifier.size(26.dp), tint = Color.Unspecified)
    } else {
        Icon(if (preset == ProviderPreset.DOUBAO) RecordIcons.Wave else RecordIcons.Cloud, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ToggleRow(title: String, description: String?, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onValue, enabled = LocalSettingsEnabled.current)
    }
}

@Composable
private fun Choice(label: String, selected: String, options: List<String>, selectedIcon: (@Composable () -> Unit)? = null, optionIcon: (@Composable (Int) -> Unit)? = null, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, enabled = LocalSettingsEnabled.current, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().testTag("choice-$label")) {
            Row(Modifier.heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                selectedIcon?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected, style = MaterialTheme.typography.titleMedium)
                }
                Icon(RecordIcons.Down, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, leadingIcon = optionIcon?.let { { it(index) } }, onClick = {
                    expanded = false
                    onSelect(index)
                })
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

private fun capabilityIcon(capability: AiCapability): ImageVector = when (capability) {
    AiCapability.ASR -> RecordIcons.Wave
    AiCapability.TITLE -> RecordIcons.Spark
    AiCapability.SUMMARY -> RecordIcons.Text
    AiCapability.MEMORY -> RecordIcons.Memory
    AiCapability.ANSWER -> RecordIcons.Chat
}

@Preview(name = "窄窗配置", widthDp = 360, heightDp = 800)
@Preview(name = "宽窗配置", widthDp = 900, heightDp = 700)
@Preview(name = "大字体配置", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun SettingsPreview() {
    RecordTheme {
        SettingsFrame("设置", SettingsUiState(), SessionState(), false, {}, null, {}, {}) {
            SectionLabel("AI 服务")
            Field("Base URL", "https://api.example.com/v1", {})
            Field("模型名", "your-transcription-model", {})
        }
    }
}
