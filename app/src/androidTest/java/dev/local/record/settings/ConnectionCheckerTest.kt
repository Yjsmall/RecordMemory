package dev.local.record.settings

import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCheckerTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun checker() = ConnectionChecker { url ->
        (URL(url).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientCertificates.sslSocketFactory() }
    }

    private fun server() = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
        start()
    }

    @Test
    fun metadataCheckUsesExactPathBearerAndNoAudioBody() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"speech-model"},{"id":"text-model"}]}"""))
            val connection = AiConnection("a", baseUrl = server.url("/proxy/v1").toString().trimEnd('/'), modelsPath = "/custom-models")
            val result = checker().check(connection, "test-key")
            assertEquals(listOf("speech-model", "text-model"), result.modelIds)
            assertTrue(result.message.contains("尚未验证"))
            val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/proxy/v1/custom-models", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun redirectsNeverForwardCredentialsAndErrorsAreSanitized() = runBlocking {
        server().use { server ->
            val connection = AiConnection("a", baseUrl = server.url("/v1").toString().trimEnd('/'))
            for (status in listOf(302, 401, 404, 429, 503)) {
                server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", server.url("/redirect")).setBody("test-key PRIVATE-SERVER-ERROR"))
                val error = runCatching { checker().check(connection, "test-key") }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertTrue(error?.message.orEmpty().contains(status.toString()))
                assertFalse(error?.message.orEmpty().contains("test-key"))
                assertFalse(error?.message.orEmpty().contains("PRIVATE"))
                assertEquals("/v1/models", requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun defaultClientRejectsUntrustedCertificateAndMalformedResponses() = runBlocking {
        server().use { server ->
            val connection = AiConnection("a", baseUrl = server.url("/v1").toString().trimEnd('/'), bearerAuth = false)
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(runCatching { ConnectionChecker().check(connection, null) }.exceptionOrNull() is SSLException)
            val result = runCatching { checker().check(connection, null) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("{}"))
            assertEquals(null, requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).getHeader("Authorization"))
        }
    }

    @Test
    fun cancellingPendingCheckReleasesRequest() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val connection = AiConnection("a", baseUrl = server.url("/v1").toString().trimEnd('/'), bearerAuth = false)
            val pending = async { checker().check(connection, null) }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertTrue(request != null)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
