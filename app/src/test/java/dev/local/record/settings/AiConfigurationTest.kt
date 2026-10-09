package dev.local.record.settings

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigurationTest {
    private val connection = AiConnection("a", name = "自建", baseUrl = "https://example.com/proxy/v1/")

    @Test
    fun endpointPreservesBasePrefixAndRejectsUnsafeAddresses() {
        assertEquals("https://example.com/proxy/v1/models", endpoint(connection, "/models"))
        listOf("http://example.com", "https://key@example.com", "https://example.com?key=secret", "https://example.com#secret", "file:///test").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { validateConnection(connection.copy(baseUrl = address)) }
        }
        listOf("//other.com/models", "/../models", "/models?key=secret", "/%2e%2e", "/foo bar").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { validateConnection(connection.copy(modelsPath = path)) }
        }
    }

    @Test
    fun exportRoundTripsIndependentBindingsAndCustomPrompts() {
        val config = AiConfiguration(
            connections = listOf(connection, connection.copy(id = "b", name = "另一个")),
            bindings = listOf(
                CapabilityBinding(AiCapability.ASR, "a", "speech-model", "zh", "专有名词"),
                CapabilityBinding(AiCapability.TITLE, "b", "text-model", "zh", "只写标题")
            )
        )
        assertEquals(config, ConfigurationCodec.parse(ConfigurationCodec.export(config)))
        assertFalse(ConfigurationCodec.export(config).contains("apiKeys"))
        assertEquals(null, config.binding(AiCapability.SUMMARY).connectionId)
    }

    @Test
    fun mergePreservesExistingEditsAndUnknownProtocolIsNotExecutable() {
        val current = AiConfiguration(connections = listOf(connection), bindings = listOf(CapabilityBinding(AiCapability.TITLE, "a", "mine", prompt = "人工模板")))
        val incoming = AiConfiguration(
            connections = listOf(connection.copy(name = "覆盖"), connection.copy(id = "b", protocol = "future-protocol")),
            bindings = listOf(CapabilityBinding(AiCapability.TITLE, "b", "new"), CapabilityBinding(AiCapability.ASR, "b", "asr"))
        )
        val merged = ConfigurationCodec.merge(current, incoming)
        assertEquals(connection, merged.connections.first())
        assertEquals("人工模板", merged.binding(AiCapability.TITLE).prompt)
        assertEquals("b", merged.binding(AiCapability.ASR).connectionId)
        assertThrows(IllegalArgumentException::class.java) { endpoint(merged.connections.last(), "/models") }
    }

    @Test
    fun invalidImportCannotSilentlyLoseBindingsOrSecrets() {
        listOf(
            AiConfiguration(schemaVersion = 2),
            AiConfiguration(connections = listOf(connection, connection)),
            AiConfiguration(bindings = listOf(CapabilityBinding(AiCapability.ASR, "missing"))),
            AiConfiguration(bindings = listOf(CapabilityBinding(AiCapability.ASR), CapabilityBinding(AiCapability.ASR)))
        ).forEach { config -> assertThrows(IllegalArgumentException::class.java) { ConfigurationCodec.parse(ConfigurationCodec.export(config)) } }
        assertThrows(Exception::class.java) { ConfigurationCodec.parse("""{"schemaVersion":1,"apiKeys":{"a":"secret"}}""") }
    }

    @Test
    fun modelsResponseRequiresActualStringIdsAndBoundsInput() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"b"}]}"""))
        listOf("{}", "html", """{"data":[{"id":12}]}""", """{"data":[{"id":""}]}""").forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { parseModelIds(source) }
        }
        assertThrows(IllegalArgumentException::class.java) { readLimited(ByteArrayInputStream(ByteArray(MAX_CONFIG_BYTES + 1))) }
    }

    @Test
    fun httpErrorsDistinguishAuthPathQuotaAndRedirectWithoutResponseBody() {
        assertTrue(httpError(401).contains("鉴权"))
        assertTrue(httpError(404).contains("路径"))
        assertTrue(httpError(429).contains("额度"))
        assertTrue(httpError(302).contains("重定向"))
        assertFalse(PrivateSettings(apiKeys = mapOf("a" to "secret")).toString().contains("secret"))
    }

    @Test
    fun providerPresetsSeparateResponseModelsFromSpeechModels() {
        val deepseek = ProviderPreset.DEEPSEEK.connection("deepseek")
        val doubao = ProviderPreset.DOUBAO.connection("doubao")
        assertEquals("https://api.deepseek.com/responses", endpoint(deepseek, deepseek.responsesPath))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), deepseek.modelSuggestions())
        assertTrue(deepseek.supports(AiCapability.TITLE))
        assertFalse(deepseek.supports(AiCapability.ASR))
        assertTrue(doubao.supports(AiCapability.ASR))
        assertFalse(doubao.supports(AiCapability.TITLE))
        assertEquals(listOf("bigmodel"), doubao.modelSuggestions())
        val invalid = AiConfiguration(connections = listOf(deepseek), bindings = listOf(CapabilityBinding(AiCapability.ASR, deepseek.id, "speech")))
        assertThrows(IllegalArgumentException::class.java) { validateConfiguration(invalid) }
    }

    @Test
    fun responseDiagnosticUsesInputInstructionsAndReadsOnlyFinalText() {
        val connection = ProviderPreset.DEEPSEEK.connection("a")
        val binding = CapabilityBinding(AiCapability.SUMMARY, "a", "deepseek-flash", prompt = "私人模板不发送", reasoningEffort = "none")
        val request = modelDiagnosticBody(connection, binding)
        assertTrue(request.contains("\"input\""))
        assertTrue(request.contains("\"instructions\""))
        assertTrue(request.contains("\"store\":false"))
        assertTrue(request.contains("\"effort\":\"none\""))
        assertFalse(request.contains("messages"))
        assertFalse(request.contains(binding.prompt))
        val source = """{"status":"completed","output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"不展示"}]},{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}"""
        assertEquals("OK", parseDiagnosticOutput(RESPONSES, source))
        assertThrows(IllegalArgumentException::class.java) { parseDiagnosticOutput(RESPONSES, source.replace("completed", "incomplete")) }
    }

    @Test
    fun extendedConfigurationRoundTripsAndOldConfigurationDefaultsRemainReadable() {
        val connections = listOf(ProviderPreset.DEEPSEEK.connection("a"), ProviderPreset.DOUBAO.connection("b"), ProviderPreset.CUSTOM_OPENAI.connection("c"))
        val config = AiConfiguration(connections = connections, bindings = listOf(CapabilityBinding(AiCapability.ASR, "b", "bigmodel"), CapabilityBinding(AiCapability.SUMMARY, "a", "deepseek-v4-pro", reasoningEffort = "high")))
        assertEquals(config, ConfigurationCodec.parse(ConfigurationCodec.export(config)))
        val old = """{"connections":[{"id":"old","name":"旧接口","protocol":"openai-compatible-v1","baseUrl":"https://example.com/v1"}]}"""
        assertTrue(ConfigurationCodec.parse(old).connections.first().supports(AiCapability.ASR))
        assertThrows(IllegalArgumentException::class.java) { validateConnection(connections[1].copy(doubaoLegacyAuth = true)) }
    }
}
