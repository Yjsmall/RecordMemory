package dev.local.record.ai

import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.OPENAI_COMPATIBLE
import dev.local.record.settings.RESPONSES
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AgentToolCall(val id: String, val name: String, val arguments: JsonObject)
data class AgentToolDefinition(val name: String, val description: String, val parameters: JsonObject)
data class AgentToolResult(val call: AgentToolCall, val output: String)

/** Continuation is ephemeral provider output, including opaque reasoning; never persist it in events. */
data class AgentModelReply(val text: String, val calls: List<AgentToolCall>, val continuation: List<JsonObject> = emptyList())
data class AgentExchange(val reply: AgentModelReply, val results: List<AgentToolResult>)

internal fun agentRequestBody(
    connection: AiConnection,
    binding: CapabilityBinding,
    messages: List<AssistantMessage>,
    tools: List<AgentToolDefinition>,
    exchanges: List<AgentExchange> = emptyList(),
    forcedToolName: String? = null
): String {
    require(connection.protocol in setOf(RESPONSES, OPENAI_COMPATIBLE)) { "此协议不支持工具调用" }
    require(binding.model.isNotBlank()) { "请先配置工具模型" }
    require(messages.isNotEmpty() && messages.all { it.role in setOf("user", "assistant") && it.text.isNotBlank() })
    require(tools.size <= 16 && tools.map { it.name }.distinct().size == tools.size)
    tools.forEach { tool ->
        require(validToolName(tool.name) && tool.description.length <= 2_000)
        require(tool.parameters["type"] == JsonPrimitive("object") && tool.parameters.toString().length <= 16_000)
    }
    require(forcedToolName == null || tools.any { it.name == forcedToolName })
    require(exchanges.size <= 3) { "工具交互超过限额" }
    require(exchanges.sumOf { exchange -> exchange.results.sumOf { it.output.length } } <= 12_000) { "工具结果超过文本限额" }
    exchanges.forEach { exchange ->
        require(exchange.reply.calls.isNotEmpty())
        require(exchange.reply.calls.map { it.id }.distinct().size == exchange.reply.calls.size)
        require(exchange.results.size == exchange.reply.calls.size)
        require(exchange.results.map { it.call.id }.distinct().size == exchange.results.size)
        require(exchange.results.all { result -> exchange.reply.calls.any { it == result.call } }) { "工具结果与调用不匹配" }
    }
    val responses = connection.protocol == RESPONSES
    val turns = messages.map { message ->
        buildJsonObject {
            put("role", message.role)
            put("content", message.text)
        }
    }
    val history = exchanges.flatMap { exchange ->
        val originals = if (exchange.reply.continuation.isNotEmpty()) {
            exchange.reply.continuation
        } else {
            if (responses) exchange.reply.calls.map(::responseCall) else listOf(chatAssistant(exchange.reply))
        }
        originals + exchange.results.map { result ->
            buildJsonObject {
                if (responses) {
                    put("type", "function_call_output")
                    put("call_id", result.call.id)
                    put("output", result.output)
                } else {
                    put("role", "tool")
                    put("tool_call_id", result.call.id)
                    put("content", result.output)
                }
            }
        }
    }
    return buildJsonObject {
        put("model", binding.model.trim())
        put("stream", false)
        put("store", false)
        put("parallel_tool_calls", false)
        put(
            "tools",
            JsonArray(
                tools.map { tool ->
                    val definition = buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                        // Preserve optional fields; argument validation belongs to the local registry.
                        put("strict", false)
                    }
                    if (responses) {
                        JsonObject(definition + ("type" to JsonPrimitive("function")))
                    } else {
                        buildJsonObject {
                            put("type", "function")
                            put("function", definition)
                        }
                    }
                }
            )
        )
        if (forcedToolName != null) {
            put(
                "tool_choice",
                buildJsonObject {
                    put("type", "function")
                    if (responses) put("name", forcedToolName) else putJsonObject("function") { put("name", forcedToolName) }
                }
            )
        } else {
            put("tool_choice", if (tools.isEmpty()) "none" else "auto")
        }
        if (responses) {
            put("input", JsonArray(turns + history))
            put("instructions", binding.prompt)
            put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
            put("max_output_tokens", 4_096)
            if (binding.reasoningEffort.isNotEmpty()) putJsonObject("reasoning") { put("effort", binding.reasoningEffort) }
        } else {
            val system = buildJsonObject {
                put("role", "system")
                put("content", binding.prompt)
            }
            put("messages", JsonArray(listOf(system) + turns + history))
            put("max_tokens", 4_096)
            if (binding.reasoningEffort.isNotEmpty()) put("reasoning_effort", binding.reasoningEffort)
        }
    }.toString().also { require(it.toByteArray().size <= 512_000) { "工具上下文超过上传限额" } }
}

/** Reads only native tool fields. Plain text (including JSON-looking text) never becomes a call. */
internal fun parseAgentReply(protocol: String, source: String): AgentModelReply {
    try {
        require(protocol in setOf(RESPONSES, OPENAI_COMPATIBLE))
        require(source.toByteArray().size <= 1_048_576)
        val parsed = Json.parseToJsonElement(source).jsonObject
        require(parsed["error"] == null || parsed["error"] == JsonNull)
        val reply = if (protocol == RESPONSES) parseResponsesAgent(parsed) else parseChatAgent(parsed)
        require(reply.calls.size <= 6 && reply.calls.map { it.id }.distinct().size == reply.calls.size)
        require(reply.calls.isNotEmpty() || reply.text.isNotBlank())
        require(reply.text.length <= 32_000)
        require(reply.continuation.sumOf { it.toString().length } <= 256_000)
        return reply
    } catch (_: Exception) {
        // Provider fragments, arguments, and credentials must never appear in an exception message.
        throw IllegalArgumentException("模型工具响应未完成或与所选协议不兼容")
    }
}

private fun parseResponsesAgent(parsed: JsonObject): AgentModelReply {
    require(parsed["status"] == JsonPrimitive("completed"))
    val output = parsed.getValue("output").jsonArray.map { it.jsonObject }
    require(output.all { it["type"]?.jsonPrimitive?.content in setOf("reasoning", "message", "function_call") })
    val calls = output.filter { it["type"] == JsonPrimitive("function_call") }.map { item ->
        require(item["status"] == null || item["status"] == JsonPrimitive("completed"))
        parseCall(item.string("call_id"), item.string("name"), item.string("arguments"))
    }
    val text = output.filter { it["type"] == JsonPrimitive("message") }.flatMap { it.getValue("content").jsonArray }
        .filter { it.jsonObject["type"] == JsonPrimitive("output_text") }
        .joinToString("\n") { it.jsonObject.string("text") }
    return AgentModelReply(text, calls, output)
}

private fun parseChatAgent(parsed: JsonObject): AgentModelReply {
    val choice = parsed.getValue("choices").jsonArray.single().jsonObject
    val message = choice.getValue("message").jsonObject
    require(message["role"] == JsonPrimitive("assistant"))
    val rawCalls = message["tool_calls"]?.takeUnless { it == JsonNull }?.jsonArray.orEmpty()
    val calls = rawCalls.map { raw ->
        val item = raw.jsonObject
        require(item["type"] == JsonPrimitive("function"))
        val function = item.getValue("function").jsonObject
        parseCall(item.string("id"), function.string("name"), function.string("arguments"))
    }
    require(choice["finish_reason"] == JsonPrimitive(if (calls.isEmpty()) "stop" else "tool_calls"))
    val content = message["content"]
    val text = if (content == null || content == JsonNull) "" else message.string("content")
    return AgentModelReply(text, calls, listOf(message))
}

private fun parseCall(id: String, name: String, arguments: String): AgentToolCall {
    require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() })
    require(validToolName(name) && arguments.length <= 8_000)
    return AgentToolCall(id, name, Json.parseToJsonElement(arguments).jsonObject)
}

private fun JsonObject.string(name: String): String {
    val value = getValue(name).jsonPrimitive
    require(value.isString)
    return value.content
}

private fun validToolName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9_-]{1,64}"))

private fun responseCall(call: AgentToolCall): JsonObject = buildJsonObject {
    put("type", "function_call")
    put("call_id", call.id)
    put("name", call.name)
    put("arguments", call.arguments.toString())
}

private fun chatAssistant(reply: AgentModelReply): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", reply.text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    put(
        "tool_calls",
        JsonArray(
            reply.calls.map { call ->
                buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", call.name)
                        put("arguments", call.arguments.toString())
                    }
                }
            }
        )
    )
}

/** Local capability proof is tied to the exact connection and model, without hashing private prompts. */
fun toolConfigurationFingerprint(connection: AiConnection, binding: CapabilityBinding): String {
    val input = buildJsonObject {
        put("adapterVersion", 1)
        put("connection", Json.encodeToJsonElement(AiConnection.serializer(), connection))
        put("model", binding.model.trim())
        put("reasoningEffort", binding.reasoningEffort)
    }.toString()
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
