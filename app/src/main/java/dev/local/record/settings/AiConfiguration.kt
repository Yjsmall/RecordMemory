package dev.local.record.settings

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val OPENAI_COMPATIBLE = "openai-compatible-v1"
const val RESPONSES = "openai-responses-v1"
const val DOUBAO_ASR = "doubao-asr-flash-v3"

val supportedProtocols = listOf(RESPONSES, OPENAI_COMPATIBLE, DOUBAO_ASR)

enum class ProviderPreset(val label: String) {
    DEEPSEEK("DeepSeek"),
    CUSTOM_RESPONSES("custom response"),
    DOUBAO("豆包 · 录音文件识别")
    ;

    fun connection(id: String): AiConnection = when (this) {
        DEEPSEEK -> AiConnection(id, name = "DeepSeek", protocol = RESPONSES, baseUrl = "https://api.deepseek.com", supportsTranscription = false)
        CUSTOM_RESPONSES -> AiConnection(id, name = "Responses 接口", protocol = RESPONSES, supportsTranscription = false)
        DOUBAO -> AiConnection(id, name = "豆包语音", protocol = DOUBAO_ASR, baseUrl = "https://openspeech.bytedance.com", transcriptionPath = "/api/v3/auc/bigmodel/recognize/flash", supportsTranscription = true)
    }

    companion object {
        fun forConnection(connection: AiConnection): ProviderPreset = when {
            connection.protocol == DOUBAO_ASR -> DOUBAO
            connection.protocol == RESPONSES && runCatching { URI(connection.baseUrl).host == "api.deepseek.com" }.getOrDefault(false) -> DEEPSEEK
            else -> CUSTOM_RESPONSES
        }
    }
}

@Serializable
enum class ProcessingMode { AUTO, MANUAL }

@Serializable
enum class AppAppearance(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

@Serializable
enum class AiCapability(val label: String, val destination: String, val defaultPrompt: String) {
    ASR("语音转写", "发送原始音频", "准确转写，保留原意，不添加录音中没有的内容。"),
    TITLE("智能标题", "发送转写文本", "根据原文给出一个简短中文标题，不超过 20 个字。只输出标题，不添加未提及的事实。"),
    SUMMARY("内容总结", "发送转写文本", "忠实总结原文。短记录给出简短摘要，长记录列出要点。只在原文有依据时提取待办和日期，信息不足时保留不确定性。"),
    MEMORY("记忆提取", "发送转写及相关记忆", "从原文提取人物、项目、偏好、约定或待办候选，并注明来源。不把想法或一次提及推断为长期事实；不确定时不提取。"),
    ANSWER("私人助手", "发送对话及已确认记忆", "你是我的私人助手。结合我已确认的记忆与当前对话，帮助我思考、规划和整理事情。用自然、简洁的中文交流；区分已知事实、建议与不确定的信息，不编造我的经历或偏好。")
}

@Serializable
data class AiConnection(
    val id: String,
    val name: String = "兼容接口",
    val protocol: String = OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val bearerAuth: Boolean = true,
    val modelsPath: String = "/models",
    val transcriptionPath: String = "/audio/transcriptions",
    val chatPath: String = "/chat/completions",
    val responsesPath: String = "/responses",
    val supportsTranscription: Boolean = protocol == OPENAI_COMPATIBLE,
    val doubaoLegacyAuth: Boolean = false,
    val doubaoAppId: String = "",
    val doubaoResourceId: String = "volc.bigasr.auc_turbo"
) {
    fun supports(capability: AiCapability): Boolean = protocol in supportedProtocols && when (capability) {
        AiCapability.ASR -> supportsTranscription || protocol == DOUBAO_ASR
        else -> protocol != DOUBAO_ASR
    }

    fun modelSuggestions(): List<String> = when {
        protocol == DOUBAO_ASR -> listOf("bigmodel")
        runCatching { URI(baseUrl).host == "api.deepseek.com" }.getOrDefault(false) -> listOf("deepseek-flash", "deepseek-v4-pro")
        else -> emptyList()
    }
}

@Serializable
data class CapabilityBinding(
    val capability: AiCapability,
    val connectionId: String? = null,
    val model: String = "",
    val language: String = "zh",
    val prompt: String = capability.defaultPrompt,
    val reasoningEffort: String = ""
)

/** Portable ordinary settings. Credentials and verification claims are deliberately absent. */
@Serializable
data class AiConfiguration(
    val schemaVersion: Int = 1,
    val connections: List<AiConnection> = emptyList(),
    val bindings: List<CapabilityBinding> = AiCapability.entries.map { CapabilityBinding(it) },
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val dynamicColors: Boolean = false,
    val processingMode: ProcessingMode = ProcessingMode.AUTO
) {
    fun binding(capability: AiCapability): CapabilityBinding {
        val binding = bindings.firstOrNull { it.capability == capability } ?: CapabilityBinding(capability)
        val legacy = "只依据提供的检索片段回答，标注来源引用。未找到依据时说明未找到，不编造事实或音频时间戳。"
        return if (capability == AiCapability.ANSWER && binding.prompt == legacy) binding.copy(prompt = capability.defaultPrompt) else binding
    }
}

/** Kept only in the encrypted local DataStore, never in portable configuration or events. */
@Serializable
data class AssistantPreferences(
    val soul: String? = null,
    val skillStates: Map<String, Boolean> = emptyMap(),
    val autoLearning: Boolean = false,
    val toolChecks: Map<String, String> = emptyMap()
)

@Serializable
data class PrivateSettings(
    val configuration: AiConfiguration = AiConfiguration(),
    val apiKeys: Map<String, String> = emptyMap(),
    val agent: AssistantPreferences = AssistantPreferences()
) {
    override fun toString() = "PrivateSettings(credentials=redacted)"
}

object ConfigurationCodec {
    val json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun export(configuration: AiConfiguration): String = json.encodeToString(configuration)

    fun parse(source: String): AiConfiguration {
        require(source.toByteArray().size <= MAX_CONFIG_BYTES) { "配置文件不能超过 1 MB" }
        val configuration = try {
            json.decodeFromString<AiConfiguration>(source)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("配置 JSON 格式不兼容，请使用随声记导出的无密钥配置")
        }
        return configuration.also(::validateConfiguration)
    }

    /** Merge adds new IDs; existing connections, bindings and local preferences win. */
    fun merge(current: AiConfiguration, incoming: AiConfiguration): AiConfiguration {
        validateConfiguration(incoming)
        val added = incoming.connections.filter { next -> current.connections.none { it.id == next.id } }
        return current.copy(
            connections = current.connections + added,
            bindings = AiCapability.entries.map { capability ->
                val existing = current.binding(capability)
                val untouched = existing.connectionId == null && existing.model.isBlank() && existing.prompt == capability.defaultPrompt && existing.language == "zh" && existing.reasoningEffort.isEmpty()
                if (untouched) incoming.binding(capability) else existing
            }
        ).also(::validateConfiguration)
    }
}

const val MAX_CONFIG_BYTES = 1_048_576

fun validateConfiguration(configuration: AiConfiguration) {
    require(configuration.schemaVersion == 1) { "不支持的配置版本，请使用版本 1" }
    require(configuration.connections.size <= 50) { "最多支持 50 个连接" }
    require(configuration.connections.map { it.id }.distinct().size == configuration.connections.size) { "连接 ID 重复" }
    configuration.connections.forEach { connection ->
        require(connection.id.isNotBlank() && connection.id.length <= 100) { "连接 ID 无效" }
        require(connection.name.isNotBlank() && connection.name.length <= 100) { "连接名称应为 1–100 个字符" }
        // Unknown protocols remain visible as unconfigured, but never become executable.
        if (connection.protocol in supportedProtocols) validateConnection(connection)
    }
    require(configuration.bindings.map { it.capability }.distinct().size == configuration.bindings.size) { "能力绑定重复" }
    configuration.bindings.forEach { binding ->
        require(binding.connectionId == null || configuration.connections.any { it.id == binding.connectionId }) { "能力引用了不存在的连接" }
        require(binding.model.length <= 200 && binding.prompt.length <= 20_000 && binding.language.length <= 32) { "模型、语言或提示词过长" }
        require(binding.reasoningEffort in listOf("", "none", "low", "medium", "high", "max")) { "推理强度配置无效" }
        val connection = configuration.connections.firstOrNull { it.id == binding.connectionId }
        require(connection == null || connection.protocol !in supportedProtocols || connection.supports(binding.capability)) { "该 provider 未配置此能力，请选择合适的服务" }
    }
}

fun validateConnection(connection: AiConnection) {
    require(connection.protocol in supportedProtocols) { "此协议尚未支持，请重新配置" }
    require(connection.name.isNotBlank() && connection.name.length <= 100) { "请输入连接名称（最多 100 字）" }
    val uri = runCatching { URI(connection.baseUrl) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) { "请输入完整 HTTPS 地址，例如 https://api.example.com/v1" }
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "地址不能含用户名、密码、查询参数或片段" }
    require(connection.baseUrl.length <= 2000) { "地址过长" }
    listOf(connection.modelsPath, connection.transcriptionPath, connection.chatPath, connection.responsesPath).forEach { path ->
        require(path.startsWith('/') && !path.startsWith("//") && path.length <= 500) { "请求路径必须以单个 / 开始" }
        require(path.none { it.isWhitespace() } && '?' !in path && '#' !in path && '\\' !in path) { "请求路径不能含空格、查询参数或片段" }
        require(path.split('/').none { it == ".." || it == "." }) { "请求路径不能包含相对路径" }
        require(runCatching { URI(path).rawPath == path }.getOrDefault(false)) { "请求路径格式无效" }
        val decoded = URI(path).path
        require(decoded.split('/').none { it == ".." || it == "." } && !decoded.startsWith("//") && '\\' !in decoded) { "请求路径不能包含编码后的相对路径" }
    }
    if (connection.protocol == DOUBAO_ASR) {
        require(connection.doubaoResourceId.isNotBlank() && connection.doubaoResourceId.length <= 200 && connection.doubaoResourceId.none { it.isWhitespace() }) { "请填写有效的豆包语音 Resource ID" }
        require(!connection.doubaoLegacyAuth || (connection.doubaoAppId.isNotBlank() && connection.doubaoAppId.length <= 100 && connection.doubaoAppId.none { it.isWhitespace() })) { "旧版鉴权需要 APP ID" }
    }
}

fun endpoint(connection: AiConnection, path: String): String {
    validateConnection(connection)
    require(path in listOf(connection.modelsPath, connection.transcriptionPath, connection.chatPath, connection.responsesPath))
    return connection.baseUrl.trimEnd('/') + path
}
