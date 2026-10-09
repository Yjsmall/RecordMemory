package dev.local.record.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/** One atomic encrypted file binds connection configuration and credentials together. */
class SettingsRepository(
    context: Context,
    fileName: String = "ai-settings.bin",
    keyAlias: String = "record.ai.settings.v1",
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val store = DataStoreFactory.create(
        serializer = EncryptedSettingsSerializer(keyAlias),
        scope = scope,
        produceFile = { File(context.noBackupFilesDir, fileName) }
    )
    val settings = store.data
    val configuration = settings.map { it.configuration }

    suspend fun saveConnection(connection: AiConnection, key: String?, clearKey: Boolean = false) {
        require(connection.protocol != DOUBAO_ASR || !connection.doubaoLegacyAuth) { "豆包仅支持新版 API Key 鉴权" }
        validateConnection(connection)
        require(key == null || (key.length <= 8192 && key.none { it == '\r' || it == '\n' })) { "密钥格式无效" }
        store.updateData { current ->
            val previous = current.configuration.connections.firstOrNull { it.id == connection.id }
            require(connection.protocol != DOUBAO_ASR || previous?.doubaoLegacyAuth != true || !key.isNullOrBlank()) { "请填写新版豆包 API Key" }
            val config = current.configuration.copy(connections = current.configuration.connections.filterNot { it.id == connection.id } + connection)
            validateConfiguration(config)
            val keys = when {
                clearKey && key.isNullOrBlank() || (!connection.bearerAuth && connection.protocol != DOUBAO_ASR) -> current.apiKeys - connection.id
                !key.isNullOrBlank() -> current.apiKeys + (connection.id to key.trim())
                else -> current.apiKeys
            }
            PrivateSettings(config, keys)
        }
    }

    suspend fun deleteConnection(id: String) {
        store.updateData { current ->
            PrivateSettings(
                current.configuration.copy(
                    connections = current.configuration.connections.filterNot { it.id == id },
                    bindings = current.configuration.bindings.map { if (it.connectionId == id) it.copy(connectionId = null, model = "") else it }
                ),
                current.apiKeys - id
            )
        }
    }

    suspend fun saveBinding(binding: CapabilityBinding) {
        store.updateData { current ->
            val config = current.configuration.copy(bindings = current.configuration.bindings.filterNot { it.capability == binding.capability } + binding)
            validateConfiguration(config)
            current.copy(configuration = config)
        }
    }

    suspend fun processingMode(mode: ProcessingMode) {
        store.updateData { it.copy(configuration = it.configuration.copy(processingMode = mode)) }
    }

    suspend fun appearance(appearance: AppAppearance, dynamicColors: Boolean) {
        store.updateData { it.copy(configuration = it.configuration.copy(appearance = appearance, dynamicColors = dynamicColors)) }
    }

    suspend fun merge(incoming: AiConfiguration) {
        store.updateData { it.copy(configuration = ConfigurationCodec.merge(it.configuration, incoming)) }
    }
}

/** AES-GCM authentication failures propagate; a corrupt store is never silently reset. */
internal class EncryptedSettingsSerializer(private val alias: String) : Serializer<PrivateSettings> {
    override val defaultValue = PrivateSettings()

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    override suspend fun readFrom(input: InputStream): PrivateSettings {
        val bytes = input.readBytes()
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "配置文件损坏或版本不支持" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val result = ConfigurationCodec.json.decodeFromString<PrivateSettings>(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString())
        validateConfiguration(result.configuration)
        return result
    }

    override suspend fun writeTo(t: PrivateSettings, output: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        output.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(ConfigurationCodec.json.encodeToString(t).toByteArray()))
    }
}
