package dev.local.record.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.record.agent.BuiltInAgentCatalog
import dev.local.record.agent.BuiltInSkill
import dev.local.record.ai.AiGateway
import dev.local.record.ai.toolConfigurationFingerprint
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConfiguration
import dev.local.record.settings.AiConnection
import dev.local.record.settings.AppAppearance
import dev.local.record.settings.AssistantPreferences
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.ConfigurationCodec
import dev.local.record.settings.ConnectionCheck
import dev.local.record.settings.ConnectionChecker
import dev.local.record.settings.DOUBAO_ASR
import dev.local.record.settings.PrivateSettings
import dev.local.record.settings.ProcessingMode
import dev.local.record.settings.ProviderPreset
import dev.local.record.settings.SettingsRepository
import dev.local.record.settings.TextModelChecker
import dev.local.record.settings.readLimited
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionDraft(
    val connection: AiConnection,
    val key: String = "",
    val hasStoredKey: Boolean = false,
    val clearKey: Boolean = false,
    val dirty: Boolean = false
) {
    override fun toString() = "ConnectionDraft(credentials=redacted)"
}

data class SettingsUiState(
    val configuration: AiConfiguration? = null,
    val connectionDraft: ConnectionDraft? = null,
    val bindingDraft: CapabilityBinding? = null,
    val bindingDirty: Boolean = false,
    val checking: Boolean = false,
    val testingModel: Boolean = false,
    val busy: Boolean = false,
    val check: ConnectionCheck? = null,
    val message: String? = null,
    val loadError: String? = null,
    val pendingImport: AiConfiguration? = null,
    val agentPreferences: AssistantPreferences = AssistantPreferences(),
    val agentPage: AgentSettingsPage? = null,
    val agentSkills: List<BuiltInSkill> = emptyList(),
    val defaultSoul: String = "",
    val soulDraft: String? = null,
    val soulDirty: Boolean = false,
    val agentCatalogError: String? = null,
    val probingTools: Boolean = false,
    val agentMemoryConfigured: Boolean = false
)

/** Activity-scoped drafts survive folding and recreation; credentials never enter saved-state bundles. */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    private var privateSettings = PrivateSettings()
    private var checkJob: Job? = null
    private var checkGeneration = 0

    init {
        viewModelScope.launch {
            try {
                repository.settings.collect {
                    privateSettings = it
                    mutable.value = mutable.value.copy(configuration = it.configuration, agentPreferences = it.agent, agentMemoryConfigured = hasConfiguredMemory(it))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loadError = "无法读取加密配置。录音仍可使用；请保留应用数据，勿卸载或清除数据。")
            }
        }
    }

    fun editConnection(id: String?) {
        cancelCheck()
        val connection = privateSettings.configuration.connections.firstOrNull { it.id == id } ?: ProviderPreset.DEEPSEEK.connection(UUID.randomUUID().toString())
        mutable.value = mutable.value.copy(
            connectionDraft = if (connection.protocol == DOUBAO_ASR && connection.doubaoLegacyAuth) {
                ConnectionDraft(connection.copy(doubaoLegacyAuth = false, doubaoAppId = ""), clearKey = true, dirty = true)
            } else {
                ConnectionDraft(connection, hasStoredKey = privateSettings.apiKeys.containsKey(connection.id))
            },
            message = if (connection.protocol == DOUBAO_ASR && connection.doubaoLegacyAuth) "请填写新版豆包 API Key；旧凭据不会作为 API Key 使用。保存前原配置保持不变。" else null,
            check = null
        )
    }

    fun updateConnection(update: (ConnectionDraft) -> ConnectionDraft) {
        cancelCheck()
        mutable.value.connectionDraft?.let { mutable.value = mutable.value.copy(connectionDraft = update(it).copy(dirty = true), message = null, check = null) }
    }

    fun applyPreset(preset: ProviderPreset) {
        updateConnection { it.copy(connection = preset.connection(it.connection.id), key = "", clearKey = it.hasStoredKey) }
    }

    fun editBinding(capability: AiCapability) {
        cancelCheck()
        mutable.value = mutable.value.copy(bindingDraft = privateSettings.configuration.binding(capability), bindingDirty = false, message = null)
    }

    fun updateBinding(update: (CapabilityBinding) -> CapabilityBinding) {
        cancelCheck()
        mutable.value.bindingDraft?.let { mutable.value = mutable.value.copy(bindingDraft = update(it), bindingDirty = true, message = null) }
    }

    fun discardDraft() {
        cancelCheck()
        mutable.value = mutable.value.copy(connectionDraft = null, bindingDraft = null, bindingDirty = false, message = null, check = null)
    }

    fun saveConnection(onSaved: () -> Unit) {
        val draft = mutable.value.connectionDraft ?: return
        operation {
            val previous = privateSettings.configuration.connections.firstOrNull { it.id == draft.connection.id }
            require(draft.connection.protocol != DOUBAO_ASR || previous?.doubaoLegacyAuth != true || draft.key.isNotBlank()) { "请填写新版豆包 API Key" }
            repository.saveConnection(draft.connection, draft.key.takeIf { it.isNotBlank() }, draft.clearKey)
            discardDraft()
            mutable.value = mutable.value.copy(message = "连接已保存")
            onSaved()
        }
    }

    fun deleteConnection(onDeleted: () -> Unit) {
        val id = mutable.value.connectionDraft?.connection?.id ?: return
        operation {
            repository.deleteConnection(id)
            discardDraft()
            mutable.value = mutable.value.copy(message = "连接已删除，相关能力已解除绑定")
            onDeleted()
        }
    }

    fun saveBinding(onSaved: () -> Unit) {
        val binding = mutable.value.bindingDraft ?: return
        operation {
            require(binding.connectionId == null || binding.model.isNotBlank()) { "请填写此能力使用的模型名" }
            repository.saveBinding(binding.copy(model = binding.model.trim(), language = binding.language.trim()))
            discardDraft()
            mutable.value = mutable.value.copy(message = "能力配置已保存")
            onSaved()
        }
    }

    fun checkConnection() {
        val draft = mutable.value.connectionDraft ?: return
        if (draft.connection.protocol == DOUBAO_ASR) return
        cancelCheck()
        val generation = checkGeneration
        mutable.value = mutable.value.copy(checking = true, message = null, check = null)
        checkJob = viewModelScope.launch {
            try {
                val key = when {
                    draft.clearKey -> null
                    draft.key.isNotBlank() -> draft.key.trim()
                    else -> privateSettings.apiKeys[draft.connection.id]
                }
                val result = ConnectionChecker().check(draft.connection, key)
                if (generation == checkGeneration) mutable.value = mutable.value.copy(check = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(message = checkError(error))
            } finally {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(checking = false)
            }
        }
    }

    private fun cancelCheck() {
        checkGeneration++
        checkJob?.cancel()
        checkJob = null
        mutable.value = mutable.value.copy(checking = false, testingModel = false, probingTools = false)
    }

    fun testTextModel() {
        val binding = mutable.value.bindingDraft ?: return
        val connection = privateSettings.configuration.connections.firstOrNull { it.id == binding.connectionId } ?: return
        cancelCheck()
        val generation = checkGeneration
        mutable.value = mutable.value.copy(testingModel = true, message = null)
        checkJob = viewModelScope.launch {
            try {
                val result = TextModelChecker().check(connection, binding, privateSettings.apiKeys[connection.id])
                if (generation == checkGeneration) mutable.value = mutable.value.copy(message = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(message = checkError(error))
            } finally {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(testingModel = false)
            }
        }
    }

    fun appearance(appearance: AppAppearance, dynamic: Boolean) = operation { repository.appearance(appearance, dynamic) }

    fun processingMode(auto: Boolean) = operation { repository.processingMode(if (auto) ProcessingMode.AUTO else ProcessingMode.MANUAL) }

    fun loadAgentCatalog(catalog: BuiltInAgentCatalog) {
        viewModelScope.launch {
            try {
                val resources = withContext(Dispatchers.IO) { catalog.defaultSoul to catalog.skills }
                mutable.value = mutable.value.copy(
                    defaultSoul = resources.first,
                    agentSkills = resources.second,
                    agentCatalogError = null,
                    soulDraft = if (mutable.value.agentPage == AgentSettingsPage.IDENTITY && !mutable.value.soulDirty) privateSettings.agent.soul ?: resources.first else mutable.value.soulDraft
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(agentCatalogError = "内置助手资源校验失败，技能暂不可用")
            }
        }
    }

    fun openAgentPage(page: AgentSettingsPage) {
        cancelCheck()
        mutable.value = mutable.value.copy(
            agentPage = page,
            soulDraft = if (page == AgentSettingsPage.IDENTITY) privateSettings.agent.soul ?: mutable.value.defaultSoul else null,
            soulDirty = false,
            message = null
        )
    }

    fun closeAgentPage() {
        cancelCheck()
        mutable.value = mutable.value.copy(agentPage = null, soulDraft = null, soulDirty = false, message = null)
    }

    fun updateSoul(value: String) {
        mutable.value = mutable.value.copy(soulDraft = value, soulDirty = value != (privateSettings.agent.soul ?: mutable.value.defaultSoul), message = null)
    }

    fun restoreDefaultSoul() = updateSoul(mutable.value.defaultSoul)

    fun usePreviousAnswerPrompt() = updateSoul(privateSettings.configuration.binding(AiCapability.ANSWER).prompt)

    fun saveSoul(onSaved: () -> Unit = {}) = operation {
        val draft = mutable.value.soulDraft ?: return@operation
        repository.saveSoul(draft.takeUnless { it == mutable.value.defaultSoul })
        mutable.value = mutable.value.copy(soulDirty = false, message = "助手身份已保存，下次对话生效")
        onSaved()
    }

    fun setSkillEnabled(id: String, enabled: Boolean) = operation {
        require(mutable.value.agentSkills.any { it.id == id }) { "此技能不存在" }
        repository.setSkillEnabled(id, enabled)
    }

    fun setAutoLearning(enabled: Boolean) = operation {
        if (enabled) {
            require(hasConfiguredMemory(privateSettings)) { "请先配置记忆提取模型与所需密钥" }
        }
        repository.setAutoLearning(enabled)
    }

    private fun hasConfiguredMemory(settings: PrivateSettings): Boolean {
        val binding = settings.configuration.binding(AiCapability.MEMORY)
        val connection = settings.configuration.connections.firstOrNull { it.id == binding.connectionId } ?: return false
        return connection.supports(AiCapability.MEMORY) && binding.model.isNotBlank() && (!connection.bearerAuth || !settings.apiKeys[connection.id].isNullOrBlank())
    }

    fun probeTools() {
        val binding = privateSettings.configuration.binding(AiCapability.ANSWER)
        val connection = privateSettings.configuration.connections.firstOrNull { it.id == binding.connectionId } ?: return
        if (binding.model.isBlank()) return
        cancelCheck()
        val generation = checkGeneration
        mutable.value = mutable.value.copy(probingTools = true, message = null)
        checkJob = viewModelScope.launch {
            try {
                val supported = AiGateway().probeTools(connection, binding, privateSettings.apiKeys[connection.id])
                if (generation == checkGeneration) {
                    repository.recordToolCheck("${connection.id}:${binding.model}", if (supported) toolConfigurationFingerprint(connection, binding) else "")
                    mutable.value = mutable.value.copy(message = if (supported) "所选模型支持主动回忆与技能工具" else "所选模型未通过工具检查，将使用普通对话与明确选中的技能")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(message = checkError(error))
            } finally {
                if (generation == checkGeneration) mutable.value = mutable.value.copy(probingTools = false)
            }
        }
    }

    fun export(resolver: ContentResolver, uri: Uri) = operation {
        val content = ConfigurationCodec.export(privateSettings.configuration)
        withContext(Dispatchers.IO) {
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出文件" }.use { it.write(content.toByteArray()) }
        }
        mutable.value = mutable.value.copy(message = "配置已导出，不含 API Key")
    }

    fun previewImport(resolver: ContentResolver, uri: Uri) = operation {
        val incoming = withContext(Dispatchers.IO) {
            val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开配置文件" }.use { input ->
                readLimited(input)
            }
            ConfigurationCodec.parse(bytes.decodeToString())
        }
        mutable.value = mutable.value.copy(pendingImport = incoming)
    }

    fun cancelImport() {
        mutable.value = mutable.value.copy(pendingImport = null)
    }

    fun confirmImport() {
        val incoming = mutable.value.pendingImport ?: return
        operation {
            repository.merge(incoming)
            mutable.value = mutable.value.copy(pendingImport = null, message = "配置已合并，请为新增连接填写密钥；尚未验证服务能力")
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.configuration == null) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(message = if (error is IllegalArgumentException) error.message else "操作失败，原配置已保留。请检查文件访问或设备存储后重试。")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    class Factory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
    }
}

private fun checkError(error: Exception): String = when (error) {
    is SSLException -> "HTTPS 证书或安全连接失败，请检查服务证书；不会跳过证书校验"
    is UnknownHostException -> "服务域名无法解析，请检查地址与网络"
    is SocketTimeoutException -> "连接检查超时，请检查网络或稍后重试"
    is IllegalArgumentException -> "配置或模型列表格式错误：${error.message?.take(160)}"
    is IllegalStateException -> error.message?.take(160) ?: "服务响应不兼容"
    else -> "连接失败，请检查网络与接口协议"
}
