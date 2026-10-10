package dev.local.record.agent

import dev.local.record.settings.AssistantPreferences
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

@Serializable
data class BuiltInSkill(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val path: String,
    val bundleHash: String,
    val files: Map<String, String>,
    val requiredTools: List<String>,
    val defaultEnabled: Boolean = true
) {
    fun enabled(preferences: AssistantPreferences): Boolean = preferences.skillStates[id] ?: defaultEnabled
}

/** The immutable text and versions actually used by one assistant attempt. */
@Serializable
data class AgentConfigurationSnapshot(
    val soul: String,
    val soulVersion: String,
    val conventions: String,
    val conventionsVersion: String,
    val skills: List<BuiltInSkill>,
    val explicitSkillId: String? = null
)

data class LoadedSkill(val metadata: BuiltInSkill, val body: String, val frontmatter: Map<String, Any?>)

@Serializable
private data class AgentCatalogDocument(
    val schemaVersion: Int,
    val soulHash: String,
    val conventionsHash: String,
    val skills: List<BuiltInSkill>
)

/** APK-only allowlist. Skill bodies and resources are read only when requested. */
class BuiltInAgentCatalog(private val openAsset: (String) -> InputStream) {
    private val document by lazy {
        Json.decodeFromString<AgentCatalogDocument>(readText("agent/catalog.json", CATALOG_LIMIT)).also(::validateCatalog)
    }
    val skills: List<BuiltInSkill> get() = document.skills
    val defaultSoul: String by lazy { verifiedText("agent/SOUL.md", IDENTITY_LIMIT, document.soulHash) }
    val conventions: String by lazy { verifiedText("agent/AGENTS.md", IDENTITY_LIMIT, document.conventionsHash) }

    fun snapshot(preferences: AssistantPreferences, explicitSkillId: String? = null): AgentConfigurationSnapshot {
        explicitSkillId?.let { requireEnabled(it, preferences) }
        val soul = preferences.soul ?: defaultSoul
        require(soul.isNotBlank() && soul.toByteArray(Charsets.UTF_8).size <= IDENTITY_LIMIT) { "身份内容无效或超限" }
        return AgentConfigurationSnapshot(
            soul = soul,
            soulVersion = sha256(soul.toByteArray(Charsets.UTF_8)),
            conventions = conventions,
            conventionsVersion = document.conventionsHash,
            skills = skills.filter { it.enabled(preferences) },
            explicitSkillId = explicitSkillId
        )
    }

    fun loadSkill(id: String, preferences: AssistantPreferences): LoadedSkill {
        val skill = requireEnabled(id, preferences)
        val text = verifiedText("agent/${skill.path}", SKILL_LIMIT, skill.files.getValue("SKILL.md"))
        val lines = text.lines()
        require(lines.firstOrNull() == "---") { "技能缺少 YAML 元数据" }
        val end = lines.indexOfFirstAfterStart { it == "---" }
        require(end > 1) { "技能 YAML 元数据未结束" }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            codePointLimit = SKILL_LIMIT
            nestingDepthLimit = 10
        }
        val yaml = Yaml(SafeConstructor(options)).load<Any>(lines.subList(1, end).joinToString("\n"))
        require(yaml is Map<*, *> && yaml.keys.all { it is String }) { "技能 YAML 必须是字段映射" }
        val name = yaml["name"] as? String
        val description = requireNotNull(yaml["description"] as? String) { "技能缺少文字说明" }
        require(name == skill.id && description == skill.description) { "技能名称或说明与目录不一致" }
        require(description.length in 1..1024) { "技能说明长度无效" }
        val metadata = yaml["metadata"] as? Map<*, *>
        require(metadata?.get("version") == skill.version) { "技能版本与目录不一致" }
        val allowed = when (val value = yaml["allowed-tools"]) {
            null -> emptyList()
            is String -> value.split(' ').filter(String::isNotBlank)
            is List<*> -> value.map { requireNotNull(it as? String) { "工具名称格式无效" } }
            else -> throw IllegalArgumentException("工具要求格式无效")
        }
        require(allowed.toSet() == skill.requiredTools.toSet()) { "技能工具要求与目录不一致" }
        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(body.isNotBlank()) { "技能正文为空" }
        return LoadedSkill(skill, body, yaml.entries.associate { (key, value) -> requireNotNull(key as? String) to value })
    }

    fun readResource(id: String, path: String, preferences: AssistantPreferences): String {
        val skill = requireEnabled(id, preferences)
        requireSafePath(path)
        require(path != "SKILL.md" && path.startsWith("references/") && path.endsWith(".md")) { "只可读取已登记的文本参考资料" }
        val hash = requireNotNull(skill.files[path]) { "技能未登记此资源" }
        return verifiedText("agent/skills/$id/$path", RESOURCE_LIMIT, hash)
    }

    private fun requireEnabled(id: String, preferences: AssistantPreferences): BuiltInSkill {
        val skill = requireNotNull(skills.firstOrNull { it.id == id }) { "此技能不存在或已移除" }
        require(skill.enabled(preferences)) { "此技能已关闭，请到设置中的技能页开启" }
        return skill
    }

    private fun validateCatalog(catalog: AgentCatalogDocument) {
        require(catalog.schemaVersion == 1) { "不支持的技能目录版本" }
        require(catalog.skills.size <= 200) { "技能目录超限" }
        require(catalog.skills.map { it.id }.distinct().size == catalog.skills.size) { "技能 ID 重复" }
        requireHash(catalog.soulHash)
        requireHash(catalog.conventionsHash)
        catalog.skills.forEach { skill ->
            require(skill.id.length in 1..64 && skill.id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "技能 ID 无效" }
            require(skill.name.isNotBlank() && skill.name.length <= 100 && skill.description.length in 1..1024) { "技能名称或说明无效" }
            require(skill.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "技能版本无效" }
            require(skill.path == "skills/${skill.id}/SKILL.md") { "技能路径与 ID 不一致" }
            requireSafePath(skill.path)
            require(skill.files.size in 1..100 && "SKILL.md" in skill.files) { "技能文件清单无效" }
            skill.files.forEach { (path, hash) ->
                requireSafePath(path)
                require(path == "SKILL.md" || (path.startsWith("references/") && path.endsWith(".md"))) { "技能包含不支持的资源" }
                requireHash(hash)
            }
            require(skill.requiredTools.distinct().size == skill.requiredTools.size && skill.requiredTools.all { it in READ_ONLY_TOOLS }) { "技能要求不支持的工具" }
            requireHash(skill.bundleHash)
            val manifest = skill.files.toSortedMap().entries.joinToString("") { (path, hash) -> "$path\u0000$hash\n" }
            require(sha256(manifest.toByteArray(Charsets.UTF_8)) == skill.bundleHash) { "技能包清单哈希不一致" }
        }
    }

    private fun verifiedText(path: String, limit: Int, hash: String): String {
        val bytes = readBytes(path, limit)
        require(sha256(bytes) == hash) { "内置资源校验失败" }
        return decodeText(bytes)
    }

    private fun readText(path: String, limit: Int): String = decodeText(readBytes(path, limit))

    private fun readBytes(path: String, limit: Int): ByteArray = openAsset(path).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "内置资源超出大小限制" }
        }
        output.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun requireSafePath(path: String) {
        require(path.isNotEmpty() && path.length <= 300 && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "资源相对路径无效" }
        require(path.all { it.isLetterOrDigit() && it.code < 128 || it in "-_/ ." } && ' ' !in path) { "资源路径包含非法字符" }
    }

    private fun requireHash(hash: String) = require(hash.matches(Regex("[a-f0-9]{64}"))) { "资源哈希格式无效" }

    private fun List<String>.indexOfFirstAfterStart(predicate: (String) -> Boolean): Int = indices.drop(1).firstOrNull { predicate(this[it]) } ?: -1

    companion object {
        const val IDENTITY_LIMIT = 32 * 1024
        const val SKILL_LIMIT = 64 * 1024
        const val RESOURCE_LIMIT = 128 * 1024
        private const val CATALOG_LIMIT = 256 * 1024
        private val READ_ONLY_TOOLS = setOf("get_personal_profile", "search_memories", "get_memory_sources", "search_local_sources", "read_skill_resource", "load_skill")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
