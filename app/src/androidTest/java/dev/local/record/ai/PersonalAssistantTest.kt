package dev.local.record.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.data.ConversationRepository
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordDatabase
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.OPENAI_COMPATIBLE
import dev.local.record.settings.RESPONSES
import dev.local.record.settings.SettingsRepository
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalAssistantTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private fun gateway() = AiGateway { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientCertificates.sslSocketFactory() } }
    private fun server() = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
        start()
    }
    private fun response(text: String): MockResponse {
        val quoted = buildJsonObject { put("text", text) }["text"].toString()
        return MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$quoted}]}]}""")
    }

    @Test fun bothProtocolsSendRealMultiTurnMessagesWithoutRemoteState() = runBlocking {
        server().use { server ->
            for (protocol in listOf(RESPONSES, OPENAI_COMPATIBLE)) {
                server.enqueue(if (protocol == RESPONSES) response("自然回复") else MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"自然回复"}}]}"""))
                val connection = AiConnection("provider", protocol = protocol, baseUrl = server.url("/v1").toString().trimEnd('/'))
                val binding = CapabilityBinding(AiCapability.ANSWER, "provider", "synthetic-model", prompt = "个人上下文")
                assertEquals("自然回复", gateway().converse(connection, binding, "synthetic-key", listOf(AssistantMessage("user", "第一问"), AssistantMessage("assistant", "第一答"), AssistantMessage("user", "第二问"))))
                val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals(if (protocol == RESPONSES) "/v1/responses" else "/v1/chat/completions", request.path)
                assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("false", body.getValue("store").jsonPrimitive.content)
                assertFalse(body.containsKey("previous_response_id"))
                val messages = body.getValue(if (protocol == RESPONSES) "input" else "messages").jsonArray
                assertEquals(if (protocol == RESPONSES) listOf("user", "assistant", "user") else listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            }
        }
    }

    @Test fun confirmedMemoryPersonalizesNextTurnAndForgettingPurgesRelatedHistory() = runBlocking {
        server().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val id = UUID.randomUUID().toString()
            val settings = SettingsRepository(context, "assistant-test-$id.bin", "assistant-test-$id", scope)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val connection = AiConnection("provider", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
                settings.saveConnection(connection, "synthetic-key")
                settings.saveBinding(CapabilityBinding(AiCapability.ANSWER, "provider", "synthetic-model"))
                val conversations = ConversationRepository(db)
                val processing = ProcessingRepository(db)
                val assistant = PersonalAssistant(conversations, processing, settings, scope, gateway())
                conversations.create("c", 1)
                conversations.request("t1", "c", "我喜欢安静的咖啡馆", 2)
                server.enqueue(response("""{"reply":"下次挑地方时可以考虑安静程度。","items":[{"type":"preference","text":"喜欢安静的咖啡馆","evidence":"我喜欢安静的咖啡馆"}]}"""))
                repeat(8) { assistant.respond("t1") }
                withTimeout(10_000) { while (conversations.turn("t1")?.status != TurnStatus.ANSWERED) delay(20) }
                server.takeRequest(3, TimeUnit.SECONDS)
                val candidate = processing.memories().single()
                assertEquals(MemoryStatus.CANDIDATE, candidate.status)
                processing.confirmMemory(candidate.id, 3)
                conversations.request("t2", "c", "周末去哪里放松？", 4)
                server.enqueue(response("""{"reply":"可以找一家环境安静的店。","items":[]}"""))
                assistant.respond("t2")
                withTimeout(10_000) { while (conversations.turn("t2")?.status != TurnStatus.ANSWERED) delay(20) }
                val second = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()
                assertTrue(second.contains("喜欢安静的咖啡馆"))
                assertTrue(second.contains("下次挑地方"))
                processing.forgetMemory(candidate.id, 5)
                conversations.request("t3", "c", "帮我整理今天的计划", 6)
                server.enqueue(response("""{"reply":"今天有哪些安排？","items":[]}"""))
                assistant.respond("t3")
                withTimeout(10_000) { while (conversations.turn("t3")?.status != TurnStatus.ANSWERED) delay(20) }
                val third = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()
                assertFalse(third.contains("咖啡馆"))
                assertFalse(third.contains("下次挑地方"))
                assertFalse(third.contains("周末去哪里"))
                assertEquals(3, server.requestCount)
                assertTrue(db.processing().pending().isEmpty())
            } finally {
                scope.cancel()
                db.close()
            }
        }
    }

    @Test fun providerFailureDoesNotRetryOrPersistCredentials() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-key PRIVATE RESPONSE"))
            val connection = AiConnection("provider", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
            val error = runCatching { gateway().converse(connection, CapabilityBinding(AiCapability.ANSWER, "provider", "model"), "synthetic-key", listOf(AssistantMessage("user", "你好"))) }.exceptionOrNull()
            assertTrue(error != null)
            assertFalse(error?.message.orEmpty().contains("synthetic-key"))
            assertFalse(error?.message.orEmpty().contains("PRIVATE RESPONSE"))
            assertEquals(1, server.requestCount)
        }
    }
}
