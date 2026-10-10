package dev.local.record.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.record.agent.BuiltInAgentCatalog
import dev.local.record.data.ConversationRepository
import dev.local.record.data.MemoryDraft
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordDatabase
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.TurnStatus
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeIntegrationTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private class Fixture(
        val server: MockWebServer,
        val db: RecordDatabase,
        val settings: SettingsRepository,
        val catalog: BuiltInAgentCatalog,
        val conversations: ConversationRepository,
        val processing: ProcessingRepository,
        val assistant: PersonalAssistant
    ) {
        suspend fun await(id: String, status: TurnStatus) = withTimeout(10_000) {
            while (conversations.turn(id)?.status != status) delay(20)
        }
        suspend fun request(text: String) {
            conversations.create("current", 10)
            conversations.request("run", "current", text, 11)
            assistant.respond("run")
        }
    }

    private suspend fun fixture(proven: Boolean = true, block: suspend (Fixture) -> Unit) {
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val id = UUID.randomUUID().toString()
            val settings = SettingsRepository(context, "agent-runtime-$id.bin", "agent-runtime-$id", scope)
            val db = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
            try {
                val connection = AiConnection("provider", protocol = RESPONSES, baseUrl = server.url("/v1").toString().trimEnd('/'))
                val binding = CapabilityBinding(AiCapability.ANSWER, "provider", "synthetic-model")
                settings.saveConnection(connection, "synthetic-key")
                settings.saveBinding(binding)
                settings.saveSoul("合成身份：请先核对证据，再回答。")
                if (proven) settings.recordToolCheck("provider:synthetic-model", toolConfigurationFingerprint(connection, binding))
                val catalog = BuiltInAgentCatalog { context.assets.open(it) }
                val conversations = ConversationRepository(db)
                val processing = ProcessingRepository(db)
                val gateway = AiGateway { (URL(it).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTls.sslSocketFactory() } }
                block(Fixture(server, db, settings, catalog, conversations, processing, PersonalAssistant(conversations, processing, settings, scope, gateway, catalog)))
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                db.close()
            }
        }
    }

    private fun answer(text: String): MockResponse {
        val encoded = buildJsonObject { put("text", text) }.getValue("text").toString()
        return MockResponse().setBody("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":$encoded}]}]}""")
    }

    private fun call(id: String, name: String, arguments: String): MockResponse {
        val encoded = buildJsonObject { put("arguments", arguments) }.getValue("arguments").toString()
        return MockResponse().setBody("""{"status":"completed","output":[{"type":"function_call","call_id":"$id","name":"$name","arguments":$encoded}]}""")
    }

    @Test
    fun disabledExplicitSkillIsRejectedBeforeUpload() = runBlocking {
        fixture { f ->
            f.settings.setSkillEnabled("weekly-review", false)
            f.request("/weekly-review 做周回顾")
            f.await("run", TurnStatus.FAILED)
            assertEquals("SKILL_DISABLED", f.conversations.turn("run")?.failure)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test
    fun oversizedIdentityHasActionableFailureWithoutUpload() = runBlocking {
        fixture { f ->
            f.settings.saveSoul("x".repeat(8_100))
            f.request("你好")
            f.await("run", TurnStatus.FAILED)
            assertEquals("CONTEXT_TOO_LONG", f.conversations.turn("run")?.failure)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test
    fun unrelatedNewCandidateDoesNotInterruptCurrentAnswer() = runBlocking {
        fixture(proven = false) { f ->
            f.server.enqueue(answer("当前问题的回复").setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS))
            f.request("讨论当前计划")
            assertTrue(f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)
            f.conversations.create("other", 20)
            f.conversations.request("other-turn", "other", "我喜欢合成茶", 21)
            f.processing.proposeConversationMemories("other-turn", listOf(MemoryDraft(MemoryKind.PREFERENCE, "喜欢合成茶", "我喜欢合成茶")), 22)
            f.await("run", TurnStatus.ANSWERED)
            assertEquals("当前问题的回复", f.conversations.turn("run")?.reply)
        }
    }

    @Test
    fun skillAndMemoryQueriesKeepDeletableReceiptsAndReplayWithoutNetwork() = runBlocking {
        fixture { f ->
            f.conversations.create("source", 1)
            f.conversations.request("source-turn", "source", "我在做合成项目", 2)
            f.conversations.start("source-turn", AssistantContext("provider", "合成服务", "synthetic-model", RESPONSES, "", emptyList(), emptyList()), 3)
            assertTrue(f.conversations.answer("source-turn", 1, "合成确认", emptyList(), 4))
            f.processing.proposeConversationMemories("source-turn", listOf(MemoryDraft(MemoryKind.PROJECT, "正在做合成项目", "我在做合成项目")), 5, explicit = true)
            val memory = f.processing.memories().single()
            f.server.enqueue(call("skill-1", "load_skill", """{"id":"weekly-review"}"""))
            f.server.enqueue(call("memory-1", "search_memories", """{"query":"合成项目"}"""))
            f.server.enqueue(answer("依据合成项目记录进行周回顾。"))
            f.request("帮我做周回顾")
            f.await("run", TurnStatus.ANSWERED)
            val requests = (1..3).map { requireNotNull(f.server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8() }
            assertTrue(requests.first().contains("合成身份"))
            assertTrue(requests[1].contains("skill-1") && requests[1].contains("下周重点"))
            assertTrue(requests[2].contains("memory-1") && requests[2].contains(memory.id))
            val turn = requireNotNull(f.conversations.turn("run"))
            val snapshot = requireNotNull(f.conversations.context(requireNotNull(turn.contextContentId)))
            assertEquals("合成身份：请先核对证据，再回答。", snapshot.agent?.soul)
            assertEquals(2, snapshot.toolReceiptIds.size)
            assertTrue(snapshot.memories.any { it.id == memory.id && it.version == memory.version })
            snapshot.toolReceiptIds.forEach { receiptId ->
                assertTrue(f.db.processing().content(receiptId)?.body?.contains("synthetic-key") == false)
            }
            f.conversations.replay()
            assertEquals(turn, f.conversations.turn("run"))
            assertEquals(3, f.server.requestCount)
            f.processing.forgetMemory(memory.id, 30)
            snapshot.toolReceiptIds.forEach { assertNull(f.db.processing().content(it)) }
            assertEquals("CONTEXT_WITHDRAWN", f.conversations.turn("run")?.failure)
        }
    }

    @Test
    fun disablingSelectedSkillCancelsInflightRequestAndLateAnswerIsNotSaved() = runBlocking {
        fixture { f ->
            f.server.enqueue(answer("迟到的合成技能回复").setBodyDelay(400, TimeUnit.MILLISECONDS))
            f.request("/weekly-review 合成周回顾")
            assertTrue(f.server.takeRequest(3, TimeUnit.SECONDS) != null)
            f.settings.setSkillEnabled("weekly-review", false)
            f.await("run", TurnStatus.CANCELLED)
            delay(600)
            assertEquals(TurnStatus.CANCELLED, f.conversations.turn("run")?.status)
            assertEquals("", f.conversations.turn("run")?.reply)
            assertTrue(f.processing.memories().isEmpty())
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test
    fun unprovenModelUsesPlainConversationWithExplicitSkillBody() = runBlocking {
        fixture(proven = false) { f ->
            f.server.enqueue(answer("根据当前输入整理合成周回顾。"))
            f.request("/weekly-review 合成周回顾")
            f.await("run", TurnStatus.ANSWERED)
            val body = Json.parseToJsonElement(requireNotNull(f.server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
            assertFalse(body.containsKey("tools"))
            assertTrue(body.getValue("instructions").jsonPrimitive.content.contains("下周重点"))
            val snapshot = requireNotNull(f.conversations.context(requireNotNull(f.conversations.turn("run")?.contextContentId)))
            assertEquals("weekly-review", snapshot.agent?.explicitSkillId)
            assertTrue(snapshot.toolReceiptIds.isEmpty())
            assertEquals(1, f.server.requestCount)
        }
    }
}
