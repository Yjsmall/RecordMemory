package dev.local.record.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun doubaoOnlySavesApiKeyAndLegacyMigrationRequiresNewCredential() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val id = UUID.randomUUID().toString()
        val repository = SettingsRepository(context, "test-$id.bin", "test-$id", scope)
        try {
            val modern = ProviderPreset.DOUBAO.connection("modern")
            repository.saveConnection(modern, "synthetic-api-key")
            repository.saveConnection(modern.copy(name = "新版豆包"), null)
            assertEquals("synthetic-api-key", repository.settings.first().apiKeys[modern.id])
            val legacy = modern.copy(id = "legacy", doubaoLegacyAuth = true, doubaoAppId = "synthetic-app-id")
            assertTrue(runCatching { repository.saveConnection(legacy, "synthetic-token") }.isFailure)
            repository.merge(AiConfiguration(connections = listOf(legacy)))
            val migrated = legacy.copy(doubaoLegacyAuth = false, doubaoAppId = "")
            assertTrue(runCatching { repository.saveConnection(migrated, null) }.isFailure)
            assertTrue(repository.settings.first().configuration.connections.first { it.id == "legacy" }.doubaoLegacyAuth)
            repository.saveConnection(migrated, "synthetic-new-key")
            assertFalse(repository.settings.first().configuration.connections.first { it.id == "legacy" }.doubaoLegacyAuth)
            assertEquals("synthetic-new-key", repository.settings.first().apiKeys["legacy"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keystoreEncryptionAuthenticatesAndRoundTripsWithoutPlaintext() = runBlocking {
        val serializer = EncryptedSettingsSerializer("test-record-${UUID.randomUUID()}")
        val settings = PrivateSettings(AiConfiguration(connections = listOf(AiConnection("a"))), mapOf("a" to "test-secret-do-not-export"))
        val output = ByteArrayOutputStream()
        serializer.writeTo(settings, output)
        val bytes = output.toByteArray()
        assertFalse(bytes.decodeToString().contains("test-secret"))
        assertEquals(settings, serializer.readFrom(ByteArrayInputStream(bytes)))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val corrupted = runCatching { serializer.readFrom(ByteArrayInputStream(bytes)) }
        assertTrue(corrupted.isFailure)
    }

    @Test
    fun atomicConfigurationChangesKeepKeysAndDeleteUnbindsCapabilities() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val id = UUID.randomUUID().toString()
        val repository = SettingsRepository(context, "test-$id.bin", "test-$id", scope)
        try {
            val connection = AiConnection("a")
            repository.saveConnection(connection, "test-secret")
            repository.saveBinding(CapabilityBinding(AiCapability.ASR, "a", "whisper-1"))
            repository.saveConnection(connection.copy(name = "重命名"), null)
            assertEquals("test-secret", repository.settings.first().apiKeys["a"])
            repository.merge(AiConfiguration(connections = listOf(connection.copy(name = "不应覆盖"), AiConnection("b"))))
            assertEquals("重命名", repository.settings.first().configuration.connections.first().name)
            assertFalse(repository.settings.first().apiKeys.containsKey("b"))
            val exported = ConfigurationCodec.export(repository.settings.first().configuration)
            assertFalse(exported.contains("test-secret"))
            assertFalse(java.io.File(context.noBackupFilesDir, "test-$id.bin").readText().contains("test-secret"))
            val reopened = EncryptedSettingsSerializer("test-$id").readFrom(java.io.File(context.noBackupFilesDir, "test-$id.bin").inputStream())
            assertEquals(repository.settings.first(), reopened)
            repository.saveConnection(connection.copy(name = "无密钥"), null, clearKey = true)
            assertFalse(repository.settings.first().apiKeys.containsKey("a"))
            repository.deleteConnection("a")
            assertEquals(null, repository.settings.first().configuration.binding(AiCapability.ASR).connectionId)
            assertEquals(1, repository.settings.first().configuration.connections.size)
        } finally {
            scope.cancel()
        }
    }
}
