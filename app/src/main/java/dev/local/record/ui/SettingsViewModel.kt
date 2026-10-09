package dev.local.record.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConfiguration
import dev.local.record.settings.AiConnection
import dev.local.record.settings.AppAppearance
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.ConfigurationCodec
import dev.local.record.settings.ConnectionCheck
import dev.local.record.settings.ConnectionChecker
import dev.local.record.settings.DOUBAO_ASR
import dev.local.record.settings.PrivateSettings
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
    val pendingImport: AiConfiguration? = null
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
                    mutable.value = mutable.value.copy(configuration = it.configuration)
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
            connectionDraft = ConnectionDraft(connection, hasStoredKey = privateSettings.apiKeys.containsKey(connection.id)),
            message = null,
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
        cancelCheck()
        val generation = checkGeneration
        mutable.value = mutable.value.copy(checking = true, message = null, check = null)
        checkJob = viewModelScope.launch {
            try {
                if (draft.connection.protocol == DOUBAO_ASR) {
                    dev.local.record.settings.validateConnection(draft.connection)
                    mutable.value = mutable.value.copy(message = "豆包配置格式有效。语音鉴权和识别需要上传测试音频，此处不发起请求；尚未验证。")
                    return@launch
                }
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
        mutable.value = mutable.value.copy(checking = false, testingModel = false)
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
