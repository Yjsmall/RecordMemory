package dev.local.record.ai

import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.OPENAI_COMPATIBLE
import dev.local.record.settings.RESPONSES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {
    private val binding = CapabilityBinding(AiCapability.ANSWER, "a", "synthetic-model", prompt = "Synthetic instructions", reasoningEffort = "low")
    private val messages = listOf(AssistantMessage("user", "Synthetic question"))
    private val tool = AgentToolDefinition("echo", "Echo a synthetic value", obj("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}"""))

    @Test
    fun responsesReturnsCallIdAndResendsReasoningWithMatchingResult() {
        val connection = AiConnection("a", protocol = RESPONSES)
        val reply = parseAgentReply(RESPONSES, """{"status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"opaque"},{"id":"fc_1","type":"function_call","call_id":"call_1","name":"echo","arguments":"{\"value\":\"sample\"}","status":"completed"}]}""")
        assertEquals("call_1", reply.calls.single().id)
        assertEquals("sample", reply.calls.single().arguments.getValue("value").jsonPrimitive.content)
        val request = obj(agentRequestBody(connection, binding, messages, listOf(tool), listOf(AgentExchange(reply, listOf(AgentToolResult(reply.calls.single(), "sample"))))))
        val input = request.getValue("input").jsonArray
        assertEquals("opaque", input[1].jsonObject.getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("fc_1", input[2].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("call_1", input[3].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("sample", input[3].jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals("echo", request.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", request.getValue("include").jsonArray.single().jsonPrimitive.content)
        assertFalse(request.getValue("store").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun chatUsesNativeNestedDefinitionsAndToolMessages() {
        val reply = parseAgentReply(OPENAI_COMPATIBLE, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"opaque","tool_calls":[{"id":"chat_1","type":"function","function":{"name":"echo","arguments":"{\"value\":\"sample\"}"}}]}}]}""")
        val request = obj(agentRequestBody(AiConnection("a"), binding, messages, listOf(tool), listOf(AgentExchange(reply, listOf(AgentToolResult(reply.calls.single(), "sample"))))))
        val turns = request.getValue("messages").jsonArray
        assertEquals("opaque", turns[2].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("chat_1", turns[2].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("tool", turns[3].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("chat_1", turns[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("sample", turns[3].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("echo", request.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun textThatLooksLikeCommandIsOnlyText() {
        val text = "{\"name\":\"echo\",\"arguments\":{}}"
        val source = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}}]}"""
        val reply = parseAgentReply(OPENAI_COMPATIBLE, source)
        assertEquals(text, reply.text)
        assertTrue(reply.calls.isEmpty())
    }

    @Test
    fun optionalFieldsAreNotNormalizedIntoMandatoryNullableArguments() {
        val optional = AgentToolDefinition("search", "Synthetic optional filter", obj("""{"type":"object","properties":{"query":{"type":"string"},"scope":{"type":"string"}},"required":["query"],"additionalProperties":false}"""))
        val request = obj(agentRequestBody(AiConnection("a", protocol = RESPONSES), binding, messages, listOf(optional)))
        val definition = request.getValue("tools").jsonArray.single().jsonObject
        assertEquals("false", definition.getValue("strict").jsonPrimitive.content)
        assertEquals(listOf("query"), definition.getValue("parameters").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun invalidArgumentsIdsDuplicateCallsAndIncompleteOutputsAreRejectedWithoutLeakingPayload() {
        val fixtures = listOf(
            """{"status":"completed","output":[{"type":"function_call","call_id":"x","name":"echo","arguments":"[] SECRET"}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"x","name":"echo","arguments":"[]"}]}""",
            """{"status":"completed","output":[{"type":"function_call","name":"echo","arguments":"{}"}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"x","name":"echo","arguments":"{}"},{"type":"function_call","call_id":"x","name":"echo","arguments":"{}"}]}""",
            """{"status":"incomplete","output":[]}""",
            """{"error":{"message":"SECRET"},"status":"failed"}"""
        )
        fixtures.forEach { source ->
            val error = runCatching { parseAgentReply(RESPONSES, source) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains("SECRET"))
        }
        assertTrue(runCatching { parseAgentReply(OPENAI_COMPATIBLE, """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""") }.isFailure)
    }

    @Test
    fun resultMismatchAndOversizedResultsNeverProduceRequest() {
        val call = AgentToolCall("a", "echo", obj("{}"))
        val reply = AgentModelReply("", listOf(call))
        for (result in listOf(AgentToolResult(call.copy(id = "b"), "sample"), AgentToolResult(call, "x".repeat(12_001)))) {
            assertTrue(runCatching { agentRequestBody(AiConnection("a"), binding, messages, listOf(tool), listOf(AgentExchange(reply, listOf(result)))) }.isFailure)
        }
    }

    @Test
    fun budgetFinalizationReturnsMatchingResultsWithToolsDisabled() {
        val call = AgentToolCall("a", "echo", obj("{}"))
        val reply = AgentModelReply("", listOf(call))
        for (protocol in listOf(RESPONSES, OPENAI_COMPATIBLE)) {
            val request = obj(agentRequestBody(AiConnection("a", protocol = protocol), binding, messages, emptyList(), listOf(AgentExchange(reply, listOf(AgentToolResult(call, "sample"))))))
            assertEquals("none", request.getValue("tool_choice").jsonPrimitive.content)
            assertTrue(request.getValue("tools").jsonArray.isEmpty())
        }
    }

    @Test
    fun fingerprintInvalidatesOnProtocolPathModelAndReasoningChange() {
        val connection = AiConnection("a")
        val proven = toolConfigurationFingerprint(connection, binding)
        assertEquals(proven, toolConfigurationFingerprint(connection, binding.copy(prompt = "Other private instructions")))
        assertTrue(proven != toolConfigurationFingerprint(connection.copy(chatPath = "/other"), binding))
        assertTrue(proven != toolConfigurationFingerprint(connection.copy(protocol = RESPONSES), binding))
        assertTrue(proven != toolConfigurationFingerprint(connection, binding.copy(model = "other")))
        assertTrue(proven != toolConfigurationFingerprint(connection, binding.copy(reasoningEffort = "high")))
    }

    private fun obj(source: String): JsonObject = Json.parseToJsonElement(source).jsonObject
}
