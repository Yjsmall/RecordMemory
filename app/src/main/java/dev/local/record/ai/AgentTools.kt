package dev.local.record.ai

import dev.local.record.agent.AgentConfigurationSnapshot
import dev.local.record.agent.BuiltInAgentCatalog
import dev.local.record.data.ConversationRepository
import dev.local.record.data.ProcessingRepository
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.currentAt
import dev.local.record.domain.personalMemoryProfile
import dev.local.record.settings.AssistantPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class AgentReadResult(val output: String, val memories: List<MemoryReference> = emptyList(), val sources: List<String> = emptyList())

/** This registry has no write, file path, network, shell or credential capability. */
internal class AgentTools(
    private val processing: ProcessingRepository,
    private val conversations: ConversationRepository,
    private val catalog: BuiltInAgentCatalog?,
    private val snapshot: AgentConfigurationSnapshot?,
    private val preferences: suspend () -> AssistantPreferences,
    val usedSkills: MutableSet<String>
) {
    val definitions = listOf(
        definition("get_personal_profile", "读取少量当前已确认的个人画像", emptyMap()),
        definition("search_memories", "按关键词及可选主体／谓词／项目范围搜索当前已确认记忆，无匹配返回空", mapOf("query" to "关键词", "subject" to "主体，可选", "predicate" to "事实谓词，可选", "scope" to "项目范围，可选"), listOf("query")),
        definition("get_memory_sources", "读取已确认记忆的保留证据片段", mapOf("id" to "记忆ID"), listOf("id")),
        definition("search_local_sources", "搜索当前已确认记忆所关联的本机原文证据；不读取无关历史", mapOf("query" to "证据关键词"), listOf("query")),
        definition("load_skill", "按ID加载已启用且本轮可用的内置技能", mapOf("id" to "技能ID"), listOf("id")),
        definition("read_skill_resource", "读取已加载技能登记的参考资料", mapOf("id" to "技能ID", "path" to "相对参考资料名称"), listOf("id", "path"))
    ).filter { catalog != null || it.name !in setOf("load_skill", "read_skill_resource") }

    suspend fun execute(call: AgentToolCall): AgentReadResult {
        val definition = definitions.firstOrNull { it.name == call.name } ?: throw IllegalArgumentException("未知工具")
        val properties = definition.parameters.getValue("properties") as JsonObject
        require(call.arguments.keys.all { it in properties }) { "工具参数含未知字段" }
        require(call.arguments.values.all { it is JsonPrimitive && it.isString && it.content.length <= 200 }) { "工具参数格式无效" }
        val required = definition.parameters.getValue("required") as JsonArray
        require(required.all { call.arguments[it.jsonPrimitive.content]?.jsonPrimitive?.content?.isNotBlank() == true }) { "工具缺少必要参数" }
        fun arg(key: String) = call.arguments[key]?.jsonPrimitive?.content.orEmpty()
        if (call.name in setOf("load_skill", "read_skill_resource")) {
            val id = arg("id")
            require(snapshot?.skills?.any { it.id == id } == true) { "此技能不在本轮配置中" }
            val prefs = preferences()
            val text = if (call.name == "load_skill") {
                require(id in usedSkills || usedSkills.size < 2) { "本轮最多加载两个技能" }
                requireNotNull(catalog).loadSkill(id, prefs).body.also { usedSkills += id }
            } else {
                require(id in usedSkills) { "请先加载技能" }
                requireNotNull(catalog).readResource(id, arg("path"), prefs)
            }
            require(text.length <= 1_700) { "技能文本超过本轮工具预算，请明确选择该技能后重试" }
            return AgentReadResult(
                buildJsonObject {
                    put("skillId", id)
                    put("text", text)
                }.toString()
            )
        }
        val all = processing.memories().filter { it.currentAt(System.currentTimeMillis()) }
        val selected = when (call.name) {
            "get_personal_profile" -> personalMemoryProfile(all).items
            "get_memory_sources" -> all.filter { it.id == arg("id") }
            else -> all.filter { memory ->
                (arg("subject").isEmpty() || memory.fact?.subject == arg("subject")) &&
                    (arg("predicate").isEmpty() || memory.fact?.predicate == arg("predicate")) &&
                    (arg("scope").isEmpty() || memory.fact?.scope == arg("scope")) && matches(memory.text + " " + memory.evidence + if (call.name == "search_local_sources") memory.fact?.sources.orEmpty().joinToString { it.evidence } else "", arg("query"))
            }.sortedBy { it.id }
        }
        val rows = mutableListOf<JsonObject>()
        val refs = mutableListOf<MemoryReference>()
        val sourceIds = mutableListOf<String>()
        for (memory in selected.take(10)) {
            val sourceMode = call.name in setOf("get_memory_sources", "search_local_sources")
            val available = if (sourceMode) {
                memory.fact?.sources.orEmpty().filter { source ->
                    conversations.sourceText(source.contentId)?.let { body -> source.start >= 0 && source.end <= body.length && body.substring(source.start, source.end) == source.evidence } == true
                }
            } else {
                emptyList()
            }
            val row = buildJsonObject {
                put("id", memory.id)
                put("version", memory.version)
                put("type", memory.type.name)
                if (sourceMode) {
                    put(
                        "evidence",
                        JsonArray(
                            available.take(3).map { source ->
                                buildJsonObject {
                                    put("sourceId", source.contentId)
                                    put("text", source.evidence.take(300))
                                    put("observedAt", source.observedAt)
                                    put("origin", source.origin)
                                }
                            }
                        )
                    )
                    if (available.isEmpty()) put("retainedEvidence", memory.evidence.take(500))
                } else {
                    put("text", memory.text)
                    memory.fact?.let { fact ->
                        put("subject", fact.subject)
                        put("predicate", fact.predicate)
                        put("scope", fact.scope)
                    }
                }
            }
            if (JsonArray(rows + row).toString().length > 1_800) break
            rows += row
            refs += MemoryReference(memory.id, memory.version)
            sourceIds += available.take(3).map { it.contentId }
        }
        return AgentReadResult(
            buildJsonObject {
                put("dataOnly", true)
                put("items", JsonArray(rows))
            }.toString(),
            refs,
            sourceIds.distinct()
        )
    }
}

private fun matches(text: String, query: String): Boolean {
    val lower = text.lowercase()
    val terms = Regex("[\\p{IsHan}]+|[a-z0-9_]+").findAll(query.lowercase()).flatMap { match ->
        if (match.value.first().code > 127 && match.value.length >= 2) match.value.windowed(2).asSequence() else sequenceOf(match.value)
    }.filter { it.length >= 2 }.toList()
    return terms.any(lower::contains)
}

private fun definition(name: String, description: String, properties: Map<String, String>, required: List<String> = emptyList()) = AgentToolDefinition(
    name,
    description,
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            JsonObject(
                properties.mapValues { (_, description) ->
                    buildJsonObject {
                        put("type", "string")
                        put("description", description)
                        put("maxLength", 200)
                    }
                }
            )
        )
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
)
