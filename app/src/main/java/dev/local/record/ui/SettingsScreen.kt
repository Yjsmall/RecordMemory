package dev.local.record.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    SettingsSection("AI 服务商", "可添加多个 provider 或自建连接，分别绑定文本能力与语音转写。没有配置也能录音和播放。")
    Text("连接", style = MaterialTheme.typography.titleMedium)
    if (config.connections.isEmpty()) Text("还没有 provider。支持 DeepSeek、Responses、自定义 OpenAI 兼容接口和豆包语音配置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    config.connections.forEach { connection ->
        ListItem(
            headlineContent = { Text(connection.name) },
            supportingContent = { Text(if (connection.protocol in supportedProtocols) "${protocolLabel(connection.protocol)} · ${connection.baseUrl}" else "协议尚未支持 · 待配置") },
            trailingContent = { Text("编辑") },
            modifier = Modifier.clickable(enabled = !state.busy) { onConnection(connection.id) }.testTag("connection-${connection.id}")
        )
    }
    OutlinedButton(onClick = { onConnection(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("add-connection")) { Text("添加连接") }
    HorizontalDivider()
    Text("模型与能力", style = MaterialTheme.typography.titleMedium)
    Text("每项能力独立选择连接和模型。保存配置不会自动上传或生成内容。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AiCapability.entries.forEach { capability ->
        val binding = config.binding(capability)
        val connection = config.connections.firstOrNull { it.id == binding.connectionId }
        ListItem(
            headlineContent = { Text(capability.label) },
            supportingContent = {
                Text(
                    when {
                        connection == null -> "未配置"
                        connection.protocol !in supportedProtocols -> "协议尚未支持 · 待配置"
                        binding.model.isBlank() -> "${connection.name} · 待填写模型"
                        else -> "${connection.name} · ${binding.model} · 尚未验证能力"
                    }
                )
            },
            trailingContent = { Text("配置") },
            modifier = Modifier.clickable(enabled = !state.busy) { onCapability(capability) }.testTag("capability-${capability.name}")
        )
    }
    Text("转写、标题、总结和记忆任务将在下一阶段接通。本地文本搜索无需模型，也不会使用 Embedding。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text("外观", style = MaterialTheme.typography.titleMedium)
    Choice("主题", config.appearance.label, AppAppearance.entries.map { it.label }) { index -> model.appearance(AppAppearance.entries[index], config.dynamicColors) }
    ToggleRow("使用系统动态配色", "关闭时使用随声记的柔和绿色", config.dynamicColors) { model.appearance(config.appearance, it) }
    HorizontalDivider()
    Text("配置迁移", style = MaterialTheme.typography.titleMedium)
    Text("JSON 仅包含连接、能力、提示词和外观；不包含 API Key 或录音。导入前预览，已有同 ID 的连接和已绑定能力优先保留。", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { export.launch("随声记-AI配置.json") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出配置（不含密钥）") }
    OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导入配置") }
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
    SettingsSection("服务商连接", "选择预设可填入地址和协议，模型在各项能力中配置。DeepSeek 支持 Responses 与 Chat Completions；豆包语音使用独立识别接口。")
    val presetLabel = when {
        connection.protocol == DOUBAO_ASR -> ProviderPreset.DOUBAO.label
        connection.baseUrl.trimEnd('/') == "https://api.deepseek.com" && connection.protocol == RESPONSES -> ProviderPreset.DEEPSEEK.label
        connection.protocol == RESPONSES -> ProviderPreset.CUSTOM_RESPONSES.label
        else -> ProviderPreset.CUSTOM_OPENAI.label
    }
    Choice("服务商预设", presetLabel, ProviderPreset.entries.map { it.label }) { model.applyPreset(ProviderPreset.entries[it]) }
    Choice("接口协议", protocolLabel(connection.protocol), supportedProtocols.map(::protocolLabel)) { index ->
        val protocol = supportedProtocols[index]
        if (protocol == DOUBAO_ASR) {
            model.applyPreset(ProviderPreset.DOUBAO)
        } else {
            model.updateConnection { it.copy(connection = it.connection.copy(protocol = protocol), key = "", clearKey = it.hasStoredKey) }
        }
    }
    if (connection.protocol !in supportedProtocols) {
        Text("导入协议 ${connection.protocol} 尚未支持。此连接不能发起请求。", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { model.updateConnection { it.copy(connection = it.connection.copy(protocol = OPENAI_COMPATIBLE)) } }) { Text("改用 OpenAI 兼容协议") }
    }
    Field("连接名称", connection.name, { value -> model.updateConnection { it.copy(connection = it.connection.copy(name = value)) } }, "connection-name")
    Field("Base URL", connection.baseUrl, { value -> model.updateConnection { it.copy(connection = it.connection.copy(baseUrl = value)) } }, "base-url", keyboard = KeyboardType.Uri)
    Text("保留地址中的 /v1 等前缀。仅支持 HTTPS，始终检查证书。手机的 localhost 指手机本身。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (connection.protocol == DOUBAO_ASR) {
        Choice("豆包鉴权", if (connection.doubaoLegacyAuth) "旧版 APP ID + Access Token" else "新版 API Key", listOf("新版 API Key", "旧版 APP ID + Access Token")) { index ->
            model.updateConnection { it.copy(connection = it.connection.copy(doubaoLegacyAuth = index == 1), key = "", clearKey = it.hasStoredKey) }
        }
        if (connection.doubaoLegacyAuth) Field("APP ID", connection.doubaoAppId, { value -> model.updateConnection { it.copy(connection = it.connection.copy(doubaoAppId = value)) } })
        Field("Resource ID", connection.doubaoResourceId, { value -> model.updateConnection { it.copy(connection = it.connection.copy(doubaoResourceId = value)) } }, "resource-id")
        Text("使用豆包语音控制台的凭据，不是火山方舟聊天模型密钥。", style = MaterialTheme.typography.bodySmall)
    } else {
        ToggleRow("Bearer API Key 鉴权", "自建服务无鉴权时可关闭", connection.bearerAuth) { value -> model.updateConnection { it.copy(connection = it.connection.copy(bearerAuth = value)) } }
        ToggleRow("提供同步语音转写接口", "仅在服务文档确认支持 /audio/transcriptions 时开启", connection.supportsTranscription) { value -> model.updateConnection { it.copy(connection = it.connection.copy(supportsTranscription = value)) } }
    }
    if (connection.bearerAuth || connection.protocol == DOUBAO_ASR) {
        OutlinedTextField(
            value = draft.key,
            onValueChange = { value -> model.updateConnection { it.copy(key = value, clearKey = false) } },
            label = {
                val keyLabel = if (connection.protocol == DOUBAO_ASR && connection.doubaoLegacyAuth) "Access Token" else "API Key"
                Text(if (draft.hasStoredKey && !draft.clearKey) "新 $keyLabel（留空保留已存密钥）" else keyLabel)
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("api-key")
        )
        Text(
            if (draft.clearKey) {
                "保存时将移除密钥"
            } else if (draft.hasStoredKey) {
                "已存密钥受 Android Keystore 保护，不会回显或导出"
            } else {
                "密钥仅加密保存在本机，不进入业务事件或普通导出"
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (draft.hasStoredKey) TextButton(onClick = { model.updateConnection { it.copy(clearKey = !it.clearKey, key = "") } }) { Text(if (draft.clearKey) "保留已存密钥" else "移除已存密钥") }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起请求路径" else "请求路径（高级）") }
    if (advanced) {
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
    HorizontalDivider()
    Text(if (connection.protocol == DOUBAO_ASR) "豆包没有兼容的模型列表端点。此处只检查配置格式，不联网；真实鉴权与识别需在后续转写任务中验证。" else "连接检查只获取模型列表，发送鉴权信息；不会发送录音、转写或提示词。列表可访问不代表模型支持转写或文本生成。", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = model::checkConnection, enabled = !state.checking && !state.busy, modifier = Modifier.fillMaxWidth().testTag("check-connection")) {
        Text(
            if (state.checking) {
                "正在检查…"
            } else if (connection.protocol == DOUBAO_ASR) {
                "检查配置格式（不联网）"
            } else {
                "检查连接与模型列表"
            }
        )
    }
    state.check?.let { result ->
        Text(result.message, color = MaterialTheme.colorScheme.primary)
        Text(result.modelIds.take(60).joinToString("\n").ifEmpty { "服务返回空模型列表，请从服务文档确认模型名。" }, style = MaterialTheme.typography.bodySmall)
    }
    if (state.configuration?.connections?.any { it.id == connection.id } == true) {
        TextButton(onClick = { delete = true }, enabled = !state.busy) { Text("删除此连接", color = MaterialTheme.colorScheme.error) }
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
    SettingsSection(binding.capability.label, "${binding.capability.destination}到选定服务。当前只保存配置，尚未执行 AI 任务。")
    Choice(
        "连接",
        connections.firstOrNull { it.id == binding.connectionId }?.name ?: "未配置",
        listOf("未配置（仅本地录音）") + connections.map { it.name }
    ) { index ->
        val selected = connections.getOrNull(index - 1)
        model.updateBinding { it.copy(connectionId = selected?.id, model = selected?.modelSuggestions()?.firstOrNull().orEmpty(), reasoningEffort = "") }
    }
    if (connections.isEmpty()) Text("请先返回设置添加连接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Field("模型名", binding.model, { value -> model.updateBinding { it.copy(model = value) } }, "model-name")
    val connection = connections.firstOrNull { it.id == binding.connectionId }
    connection?.modelSuggestions()?.takeIf { it.isNotEmpty() }?.let { suggestions ->
        Choice("文档预设模型", binding.model.ifBlank { "选择模型" }, suggestions) { index -> model.updateBinding { it.copy(model = suggestions[index]) } }
    }
    if (connection?.protocol == RESPONSES) {
        val efforts = listOf("", "none", "low", "medium", "high", "max")
        val labels = listOf("遵循服务默认值", "关闭思考 · none", "低 · low", "中 · medium", "高 · high", "最高 · max")
        Choice("推理强度", labels[efforts.indexOf(binding.reasoningEffort).coerceAtLeast(0)], labels) { index -> model.updateBinding { it.copy(reasoningEffort = efforts[index]) } }
        Text("通过 /responses 发送 input 与 instructions。不同服务的推理等级和参数支持有差异，以服务文档为准。", style = MaterialTheme.typography.bodySmall)
    }
    Text("填写服务提供的精确模型 ID。聊天模型与转写模型不能通用；模型列表只供参考。", style = MaterialTheme.typography.bodySmall)
    Field("语言", binding.language, { value -> model.updateBinding { it.copy(language = value) } }, "language")
    Text(if (binding.capability == AiCapability.ASR) "默认 zh（中文）；语言代码及支持范围以所选语音服务为准，留空使用服务默认值。" else "默认 zh（中文）；用于后续文本生成的语言偏好。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = binding.prompt,
        onValueChange = { value -> model.updateBinding { it.copy(prompt = value) } },
        label = { Text(if (binding.capability == AiCapability.ASR) "转写提示（服务支持时使用）" else "提示词模板") },
        minLines = 5,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("prompt")
    )
    TextButton(onClick = { model.updateBinding { it.copy(prompt = it.capability.defaultPrompt) } }, enabled = !state.busy) { Text("恢复默认提示词") }
    if (binding.capability == AiCapability.ASR) {
        Text(
            if (connection?.protocol == DOUBAO_ASR) {
                "豆包使用 model_name（默认 bigmodel）与 Resource ID 选择语音模型。原始录音保持 M4A；当前识别接口的上传格式需另行适配，尚未接通音频上传。"
            } else {
                "按 OpenAI 兼容同步转写接口配置。原始录音为 M4A，大小、时长和格式支持由所选服务决定；尚未验证识别能力。"
            },
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        Text("测试仅发送固定的连接检查文字，使用所选模型；会消耗服务额度，不发送录音或你的提示词。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmTest = true }, enabled = connection != null && binding.model.isNotBlank() && !state.testingModel && !state.busy, modifier = Modifier.fillMaxWidth().testTag("test-text-model")) {
            Text(if (state.testingModel) "正在测试模型…" else "测试文本模型（调用 API）")
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
    OPENAI_COMPATIBLE -> "OpenAI 兼容 · Chat Completions"
    DOUBAO_ASR -> "豆包录音文件识别 · Flash"
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
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("settings-screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, enabled = !state.busy) { Text("返回") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                onSave?.let { save -> TextButton(onClick = save, enabled = !state.busy) { Text("保存") } }
            }
            if (session.active) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.busy) CircularProgressIndicator()
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("settings-message")) }
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
private fun SettingsSection(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, tag: String = label, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag), enabled = LocalSettingsEnabled.current, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
private fun ToggleRow(title: String, description: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onValue, enabled = LocalSettingsEnabled.current)
    }
}

@Composable
private fun Choice(label: String, selected: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = LocalSettingsEnabled.current, modifier = Modifier.fillMaxWidth()) { Text("$selected ▾") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        expanded = false
                        onSelect(index)
                    })
                }
            }
        }
    }
}

@Preview(name = "窄窗配置", widthDp = 360, heightDp = 800)
@Preview(name = "宽窗配置", widthDp = 900, heightDp = 700)
@Preview(name = "大字体配置", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun SettingsPreview() {
    RecordTheme {
        SettingsFrame("设置", SettingsUiState(), SessionState(), false, {}, null, {}, {}) {
            SettingsSection("AI 服务", "连接、模型与能力。没有配置也能录音和播放。")
            Field("Base URL", "https://api.example.com/v1", {})
            Field("模型名", "your-transcription-model", {})
        }
    }
}
