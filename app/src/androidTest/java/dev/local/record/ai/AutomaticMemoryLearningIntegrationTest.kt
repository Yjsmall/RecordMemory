package dev.local.record.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.data.ConversationRepository
import dev.local.record.data.MemoryPlanningRepository
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordDatabase
import dev.local.record.data.RecordingRepository
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryPlanningStatus
import dev.local.record.domain.MemoryStatus
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMemoryLearningIntegrationTest {
    private suspend fun answered(conversations: ConversationRepository, turnId: String) {
        conversations.create("conversation-$turnId", 1)
        conversations.request(turnId, "conversation-$turnId", "我喜欢咖啡", 2)
        conversations.start(turnId, AssistantContext("answer", "回答", "model", "openai-compatible-v1", "", emptyList(), emptyList()), 3)
        assertTrue(conversations.answer(turnId, 1, "了解你的偏好。", emptyList(), 4))
    }

    private fun response(): MockResponse {
        val plan = """{"schemaVersion":2,"items":[{"action":"ADD","type":"preference","text":"我喜欢咖啡","evidence":"我喜欢咖啡","fact":{"subject":"self","predicate":"drink.coffee","scope":""}}]}"""
        val text = buildJsonObject { put("text", plan) }["text"].toString()
        return MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$text}]}]}""")
    }

    @Test fun optInProcessesOnlyLiveCallbacksOnceAndRebuildCannotUploadHistory() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val id = UUID.randomUUID().toString()
            val settings = SettingsRepository(context, "auto-memory-$id.bin", "auto-memory-$id", scope)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val connection = AiConnection("memory", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
                settings.saveConnection(connection, "synthetic-key")
                settings.saveBinding(CapabilityBinding(AiCapability.MEMORY, connection.id, "synthetic-memory"))
                val conversations = ConversationRepository(db)
                val plans = MemoryPlanningRepository(db)
                val processing = ProcessingRepository(db)
                val gateway = AiGateway { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTls.sslSocketFactory() } }
                val planner = MemoryPlanner(plans, conversations, processing, settings, scope, gateway)
                val learning = AutomaticMemoryLearning(conversations, plans, planner, settings, scope)
                answered(conversations, "historical")
                learning.start()
                assertNull(planner.request("historical", automatic = true))
                assertTrue(plans.tasks.first().isEmpty())
                settings.setAutoLearning(true)
                delay(100)
                RecordingRepository(db).rebuild()
                assertTrue(plans.tasks.first().isEmpty())
                assertEquals(0, server.requestCount)
                answered(conversations, "live")
                server.enqueue(response())
                repeat(8) { learning.onAnswered("live") }
                withTimeout(10_000) { plans.tasks.first { it.singleOrNull()?.status == MemoryPlanningStatus.COMPLETED } }
                assertEquals(1, server.requestCount)
                assertEquals(MemoryStatus.CANDIDATE, processing.memories().single().status)
                assertTrue(requireNotNull(plans.input(plans.tasks.first().single())).automatic)
                repeat(8) { planner.request("live", automatic = true) }
                RecordingRepository(db).rebuild()
                assertEquals(1, plans.tasks.first().size)
                assertEquals(1, server.requestCount)
                assertFalse(processing.memories().any { it.status == MemoryStatus.CONFIRMED })
                assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                db.close()
            }
        }
    }

    @Test fun disabledSettingBlocksAutomaticUploadButPreservesExplicitManualExtraction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val id = UUID.randomUUID().toString()
        val settings = SettingsRepository(context, "auto-disabled-$id.bin", "auto-disabled-$id", scope)
        val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
        try {
            val connection = AiConnection("memory")
            settings.saveConnection(connection, "synthetic-key")
            settings.saveBinding(CapabilityBinding(AiCapability.MEMORY, connection.id, "synthetic-memory"))
            val conversations = ConversationRepository(db)
            val plans = MemoryPlanningRepository(db)
            val planner = MemoryPlanner(plans, conversations, ProcessingRepository(db), settings, scope, AiGateway { throw AssertionError("Disabled learning uploaded a source") })
            answered(conversations, "source")
            assertNull(planner.request("source", automatic = true))
            assertTrue(plans.tasks.first().isEmpty())
            val manual = plans.request("source", connection, settings.settings.first().configuration.binding(AiCapability.MEMORY), emptyList(), 5)
            planner.cancelAutomatic()
            assertEquals(MemoryPlanningStatus.REQUESTED, plans.task(manual.id)?.status)
            assertFalse(requireNotNull(plans.input(manual)).automatic)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    @Test fun disablingCancelsRunningAndQueuedAutomaticTasksWithoutCancellingManualTask() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val id = UUID.randomUUID().toString()
            val settings = SettingsRepository(context, "auto-cancel-$id.bin", "auto-cancel-$id", scope)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val connection = AiConnection("memory", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
                val binding = CapabilityBinding(AiCapability.MEMORY, connection.id, "synthetic-memory")
                settings.saveConnection(connection, "synthetic-key")
                settings.saveBinding(binding)
                settings.setAutoLearning(true)
                val conversations = ConversationRepository(db)
                val plans = MemoryPlanningRepository(db)
                val gateway = AiGateway { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTls.sslSocketFactory() } }
                val planner = MemoryPlanner(plans, conversations, ProcessingRepository(db), settings, scope, gateway)
                val learning = AutomaticMemoryLearning(conversations, plans, planner, settings, scope)
                learning.start()
                answered(conversations, "running")
                answered(conversations, "queued")
                answered(conversations, "manual")
                val manual = plans.request("manual", connection, binding, emptyList(), 5)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                planner.request("running", automatic = true)
                assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
                planner.request("queued", automatic = true)
                settings.setAutoLearning(false)
                withTimeout(10_000) {
                    plans.tasks.first { tasks -> tasks.count { it.turnId != "manual" && it.status == MemoryPlanningStatus.CANCELLED } == 2 }
                }
                assertEquals(MemoryPlanningStatus.REQUESTED, plans.task(manual.id)?.status)
                assertTrue(ProcessingRepository(db).memories().isEmpty())
                assertEquals(1, server.requestCount)
                settings.setAutoLearning(true)
                assertNull(planner.request("queued", automatic = true))
                assertEquals(1, server.requestCount)
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                db.close()
            }
        }
    }
}
