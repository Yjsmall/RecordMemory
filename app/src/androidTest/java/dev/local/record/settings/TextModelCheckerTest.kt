package dev.local.record.settings

import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextModelCheckerTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private fun checker() = TextModelChecker { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientCertificates.sslSocketFactory() } }

    @Test
    fun responsesAndChatUseDistinctProtocolPathsAndBodies() = runBlocking {
        MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }.use { server ->
            val response = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}"""
            val chat = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}"""
            for (protocol in listOf(RESPONSES, OPENAI_COMPATIBLE)) {
                server.enqueue(MockResponse().setBody(if (protocol == RESPONSES) response else chat))
                val connection = AiConnection("a", protocol = protocol, baseUrl = server.url("/v1").toString().trimEnd('/'))
                val binding = CapabilityBinding(AiCapability.TITLE, "a", "synthetic-model", prompt = "NOT-SENT-PRIVATE-PROMPT")
                val result = checker().check(connection, binding, "synthetic-key")
                assertTrue(result.contains(if (protocol == RESPONSES) "Responses" else "Chat Completions"))
                val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals(if (protocol == RESPONSES) "/v1/responses" else "/v1/chat/completions", request.path)
                assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
                val body = request.body.readUtf8()
                assertFalse(body.contains(binding.prompt))
                assertTrue(body.contains(if (protocol == RESPONSES) "\"input\"" else "\"messages\""))
            }
        }
    }

    @Test
    fun incompleteErrorAndRedirectResponsesNeverReportModelSuccess() = runBlocking {
        MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }.use { server ->
            val connection = AiConnection("a", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
            val binding = CapabilityBinding(AiCapability.TITLE, "a", "synthetic-model")
            server.enqueue(MockResponse().setBody("""{"status":"incomplete","output":[]}"""))
            assertTrue(runCatching { checker().check(connection, binding, "synthetic-key") }.isFailure)
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-key SECRET"))
            val error = runCatching { checker().check(connection, binding, "synthetic-key") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("401"))
            assertFalse(error?.message.orEmpty().contains("synthetic-key"))
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/other")))
            assertTrue(runCatching { checker().check(connection, binding, "synthetic-key") }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }
}
