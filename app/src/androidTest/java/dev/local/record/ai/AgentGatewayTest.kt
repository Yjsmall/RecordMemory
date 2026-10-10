package dev.local.record.ai

import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.OPENAI_COMPATIBLE
import dev.local.record.settings.RESPONSES
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGatewayTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private fun gateway() = AiGateway { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientCertificates.sslSocketFactory() } }
    private fun server() = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
        start()
    }

    @Test
    fun syntheticProbeRequiresNativeToolCallAndResultRoundTripForBothProtocols() = runBlocking {
        server().use { server ->
            for (protocol in listOf(RESPONSES, OPENAI_COMPATIBLE)) {
                val first = if (protocol == RESPONSES) {
                    """{"status":"completed","output":[{"type":"reasoning","id":"rs_probe","summary":[],"encrypted_content":"opaque"},{"type":"function_call","id":"fc_probe","call_id":"probe_1","name":"record_echo_probe","arguments":"{\"value\":\"synthetic\"}","status":"completed"}]}"""
                } else {
                    """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"probe_1","type":"function","function":{"name":"record_echo_probe","arguments":"{\"value\":\"synthetic\"}"}}]}}]}"""
                }
                val second = if (protocol == RESPONSES) {
                    """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"record-probe-success"}]}]}"""
                } else {
                    """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"record-probe-success"}}]}"""
                }
                server.enqueue(MockResponse().setBody(first))
                server.enqueue(MockResponse().setBody(second))
                val connection = AiConnection("a", protocol = protocol, baseUrl = server.url("/v1").toString().trimEnd('/'))
                val binding = CapabilityBinding(AiCapability.ANSWER, "a", "synthetic-model", prompt = "PRIVATE-NOT-SENT")
                assertTrue(gateway().probeTools(connection, binding, "synthetic-key"))
                val initial = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                val followup = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals(if (protocol == RESPONSES) "/v1/responses" else "/v1/chat/completions", initial.path)
                assertEquals(initial.path, followup.path)
                assertEquals("Bearer synthetic-key", initial.getHeader("Authorization"))
                val body = initial.body.readUtf8()
                assertFalse(body.contains(binding.prompt))
                assertFalse(body.contains("record-probe-success"))
                assertTrue(body.contains("record_echo_probe"))
                val history = Json.parseToJsonElement(followup.body.readUtf8()).jsonObject.getValue(if (protocol == RESPONSES) "input" else "messages").jsonArray
                val result = history.last().jsonObject
                assertEquals("probe_1", result.getValue(if (protocol == RESPONSES) "call_id" else "tool_call_id").jsonPrimitive.content)
                assertEquals("record-probe-success", result.getValue(if (protocol == RESPONSES) "output" else "content").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun unsupportedOrPseudoToolsAndHttpErrorsNeverProveCapability() = runBlocking {
        server().use { server ->
            val connection = AiConnection("a", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
            val binding = CapabilityBinding(AiCapability.ANSWER, "a", "synthetic-model")
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"name\":\"record_echo_probe\"}"}]}]}"""))
            assertFalse(gateway().probeTools(connection, binding, "synthetic-key"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-key PRIVATE"))
            assertFalse(gateway().probeTools(connection, binding, "synthetic-key"))
            assertFalse(gateway().probeTools(connection.copy(protocol = "unknown"), binding, "synthetic-key"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun nativeCallAloneDoesNotProveSuccessfulResultHandling() = runBlocking {
        server().use { server ->
            val connection = AiConnection("a", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
            val binding = CapabilityBinding(AiCapability.ANSWER, "a", "synthetic-model")
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"function_call","call_id":"probe_1","name":"record_echo_probe","arguments":"{\"value\":\"synthetic\"}"}]}"""))
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ignored result"}]}]}"""))
            assertFalse(gateway().probeTools(connection, binding, "synthetic-key"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun httpErrorIsSanitizedAndRedirectDoesNotResendCredentials() = runBlocking {
        server().use { server ->
            val connection = AiConnection("a", baseUrl = server.url("/v1").toString().trimEnd('/'))
            val binding = CapabilityBinding(AiCapability.ANSWER, "a", "synthetic-model")
            val tool = AgentToolDefinition("echo", "Synthetic tool", Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject)
            server.enqueue(MockResponse().setResponseCode(500).setBody("synthetic-key PRIVATE"))
            val error = runCatching { gateway().agentStep(connection, binding, "synthetic-key", listOf(AssistantMessage("user", "Synthetic question")), listOf(tool)) }.exceptionOrNull()
            assertTrue(error is TransientAiException)
            assertFalse(error?.message.orEmpty().contains("synthetic-key"))
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/other")))
            assertTrue(runCatching { gateway().agentStep(connection, binding, "synthetic-key", listOf(AssistantMessage("user", "Synthetic question")), listOf(tool)) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
}
