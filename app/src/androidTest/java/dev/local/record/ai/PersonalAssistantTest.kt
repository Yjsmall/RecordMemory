package dev.local.record.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.data.ConversationRepository
import dev.local.record.data.MemoryDraft
import dev.local.record.data.MemoryPlanningRepository
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordDatabase
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryReference
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
                assertTrue(processing.memories().isEmpty())
                processing.proposeConversationMemories("t1", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢安静的咖啡馆", "我喜欢安静的咖啡馆")), 3)
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
                scope.coroutineContext[Job]?.cancelAndJoin()
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

    @Test fun independentMemoryModelCreatesOnlyCandidatesForBothProtocols() = runBlocking {
        for (protocol in listOf(RESPONSES, OPENAI_COMPATIBLE)) {
            server().use { server ->
                val context = ApplicationProvider.getApplicationContext<Context>()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val id = UUID.randomUUID().toString()
                val settings = SettingsRepository(context, "planner-test-$id.bin", "planner-test-$id", scope)
                val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
                try {
                    val connection = AiConnection("provider", protocol = protocol, baseUrl = server.url("/v1").toString().trimEnd('/'))
                    settings.saveConnection(connection, "synthetic-key")
                    settings.saveBinding(CapabilityBinding(AiCapability.ANSWER, "provider", "answer-model"))
                    val conversations = ConversationRepository(db)
                    val processing = ProcessingRepository(db)
                    val plans = MemoryPlanningRepository(db)
                    val assistant = PersonalAssistant(conversations, processing, settings, scope, gateway())
                    val planner = MemoryPlanner(plans, conversations, processing, settings, scope, gateway())
                    conversations.create("c", 1)
                    conversations.request("t", "c", "我喜欢简短的回答", 2)
                    fun enqueue(text: String) {
                        if (protocol == RESPONSES) {
                            server.enqueue(response(text))
                        } else {
                            val quoted = buildJsonObject { put("text", text) }["text"].toString()
                            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":$quoted}}]}"""))
                        }
                    }
                    enqueue("以后交流时可以先说重点。")
                    assistant.respond("t")
                    withTimeout(10_000) { while (conversations.turn("t")?.status != TurnStatus.ANSWERED) delay(20) }
                    assertTrue(processing.memories().isEmpty())
                    assertTrue(runCatching { planner.request("t") }.isFailure)
                    assertEquals(1, server.requestCount)
                    assertTrue(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8().contains("answer-model"))
                    settings.saveBinding(CapabilityBinding(AiCapability.MEMORY, "provider", "memory-model"))
                    enqueue("""{"schemaVersion":1,"items":[{"action":"ADD","type":"preference","text":"喜欢简短回答","evidence":"我喜欢简短的回答"}]}""")
                    repeat(6) { planner.request("t") }
                    withTimeout(10_000) { while (db.memoryPlanning().all().singleOrNull()?.status != "COMPLETED") delay(20) }
                    val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()
                    assertTrue(request.contains("memory-model"))
                    assertFalse(request.contains("以后交流时"))
                    assertEquals(MemoryStatus.CANDIDATE, processing.memories().single().status)
                    assertEquals(MemoryPlanningStatus.COMPLETED, plans.task(db.memoryPlanning().all().single().id)?.status)
                    assertEquals(2, server.requestCount)
                } finally {
                    scope.coroutineContext[Job]?.cancelAndJoin()
                    db.close()
                }
            }
        }
    }

    private class Fixture(
        val server: MockWebServer,
        val db: RecordDatabase,
        val conversations: ConversationRepository,
        val processing: ProcessingRepository,
        val plans: MemoryPlanningRepository,
        val assistant: PersonalAssistant,
        val planner: MemoryPlanner
    ) {
        suspend fun answered(id: String) {
            conversations.create("c-$id", 1)
            conversations.request(id, "c-$id", "我喜欢简短回答", 2)
            conversations.start(id, AssistantContext("provider", "合成服务", "answer", RESPONSES, "", emptyList(), emptyList()), 3)
            conversations.answer(id, 1, "合成回复", emptyList(), 4)
        }
    }

    private suspend fun fixture(timeoutMillis: Long = 120_000, block: suspend (Fixture) -> Unit) {
        server().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val id = UUID.randomUUID().toString()
            val settings = SettingsRepository(context, "review-test-$id.bin", "review-test-$id", scope)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val connection = AiConnection("provider", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
                settings.saveConnection(connection, "synthetic-key")
                settings.saveBinding(CapabilityBinding(AiCapability.ANSWER, "provider", "answer"))
                settings.saveBinding(CapabilityBinding(AiCapability.MEMORY, "provider", "memory"))
                val conversations = ConversationRepository(db)
                val processing = ProcessingRepository(db)
                val plans = MemoryPlanningRepository(db)
                block(Fixture(server, db, conversations, processing, plans, PersonalAssistant(conversations, processing, settings, scope, gateway()), MemoryPlanner(plans, conversations, processing, settings, scope, gateway(), timeoutMillis)))
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                db.close()
            }
        }
    }

    @Test fun plannerTimeoutIsFailedAndOnlyExplicitRetryCallsProviderAgain() = runBlocking {
        fixture(timeoutMillis = 1_000) { f ->
            f.answered("t")
            f.server.enqueue(response("""{"schemaVersion":1,"items":[]}""").setBodyDelay(3, TimeUnit.SECONDS))
            f.planner.request("t")
            withTimeout(10_000) { while (f.db.memoryPlanning().all().singleOrNull()?.failure != "TIMEOUT") delay(20) }
            val first = f.db.memoryPlanning().all().single()
            assertEquals("FAILED", first.status)
            assertEquals(1, f.server.requestCount)
            assertTrue(f.processing.memories().isEmpty())
            f.server.enqueue(response("""{"schemaVersion":1,"items":[]}"""))
            f.planner.request("t")
            withTimeout(10_000) { while (f.db.memoryPlanning().all().none { it.status == "COMPLETED" }) delay(20) }
            assertEquals(2, f.server.requestCount)
            assertEquals(2, f.db.memoryPlanning().all().size)
        }
    }

    @Test fun revokedQueuedPlannerNeverUploadsAfterItsPredecessorStops() = runBlocking {
        fixture { f ->
            f.answered("first")
            f.answered("queued")
            f.server.enqueue(response("""{"schemaVersion":1,"items":[]}""").setBodyDelay(3, TimeUnit.SECONDS))
            f.planner.request("first")
            requireNotNull(f.server.takeRequest(3, TimeUnit.SECONDS))
            f.planner.request("queued")
            val queued = f.db.memoryPlanning().all().single { it.turnId == "queued" }
            assertEquals("REQUESTED", queued.status)
            f.conversations.delete("c-queued", 5)
            val first = f.db.memoryPlanning().all().single { it.turnId == "first" }
            f.planner.cancel(first.id)
            delay(200)
            assertEquals(1, f.server.requestCount)
            assertEquals("CANCELLED", f.plans.task(queued.id)?.status?.name)
            assertTrue(f.processing.memories().isEmpty())
        }
    }

    @Test fun forgettingCancelsInFlightAssistantAndClearsItsContext() = runBlocking {
        fixture { f ->
            f.answered("source")
            f.processing.proposeConversationMemories("source", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢简短回答", "我喜欢简短回答")), 5, explicit = true)
            val memory = f.processing.memories().single()
            f.conversations.create("next", 6)
            f.conversations.request("in-flight", "next", "给我建议", 7)
            f.server.enqueue(response("根据旧偏好生成的迟到回复").setBodyDelay(1, TimeUnit.SECONDS))
            f.assistant.respond("in-flight")
            requireNotNull(f.server.takeRequest(3, TimeUnit.SECONDS))
            val running = requireNotNull(f.conversations.turn("in-flight"))
            assertTrue(requireNotNull(running.contextContentId).let { f.conversations.context(it)?.memories?.contains(MemoryReference(memory.id, memory.version)) } == true)
            f.processing.forgetMemory(memory.id, 8)
            delay(1_200)
            assertEquals("CONTEXT_WITHDRAWN", f.conversations.turn("in-flight")?.failure)
            assertEquals("", f.conversations.turn("in-flight")?.reply)
            assertTrue(f.db.processing().content(requireNotNull(running.contextContentId)) == null)
            assertEquals(1, f.server.requestCount)
        }
    }
}
