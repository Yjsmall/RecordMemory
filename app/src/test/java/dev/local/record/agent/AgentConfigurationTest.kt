package dev.local.record.agent

import dev.local.record.settings.AssistantPreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigurationTest {
    @Test
    fun snapshotOnlyReadsMetadataAndKeepsPersonalIdentityFixed() {
        val reads = mutableListOf<String>()
        val catalog = BuiltInAgentCatalog { path ->
            reads += path
            assets(path)
        }
        val snapshot = catalog.snapshot(AssistantPreferences(soul = "简洁地交流"))
        assertEquals("简洁地交流", snapshot.soul)
        assertEquals(3, snapshot.skills.size)
        assertFalse(reads.any { it.endsWith("SKILL.md") })
        assertFalse(reads.any { it.contains("references/") })
        assertTrue(catalog.loadSkill("weekly-review", AssistantPreferences()).body.contains("周回顾"))
        assertEquals("简洁地交流", snapshot.soul)
    }

    @Test
    fun disabledSkillsCannotBeSelectedLoadedOrReadById() {
        val catalog = BuiltInAgentCatalog(::assets)
        val preferences = AssistantPreferences(skillStates = mapOf("weekly-review" to false))
        assertFalse(catalog.snapshot(preferences).skills.any { it.id == "weekly-review" })
        assertRejected { catalog.snapshot(preferences, "weekly-review") }
        assertRejected { catalog.loadSkill("weekly-review", preferences) }
        assertRejected { catalog.readResource("weekly-review", "references/review-outline.md", preferences) }
        assertRejected { catalog.loadSkill("unknown", preferences) }
    }

    @Test
    fun resourceAccessRejectsTraversalCrossPackageAndUndeclaredPaths() {
        val catalog = BuiltInAgentCatalog(::assets)
        val preferences = AssistantPreferences()
        listOf("../project-review/SKILL.md", "/SOUL.md", "references\\review-outline.md", "references/missing.md", "references/%2e%2e/SOUL.md").forEach { path ->
            assertRejected { catalog.readResource("weekly-review", path, preferences) }
        }
        assertTrue(catalog.readResource("weekly-review", "references/review-outline.md", preferences).isNotBlank())
    }

    @Test
    fun alteredBundleTextFailsInsteadOfBeingUsed() {
        val catalog = BuiltInAgentCatalog { path ->
            if (path.endsWith("weekly-review/SKILL.md")) ByteArrayInputStream("altered".toByteArray()) else assets(path)
        }
        assertRejected { catalog.loadSkill("weekly-review", AssistantPreferences()) }
    }

    @Test
    fun duplicateIdsAndUnsafeCatalogPathsAreRejected() {
        val source = assets("agent/catalog.json").bufferedReader().use { it.readText() }
        val parsed = Json.parseToJsonElement(source).jsonObject
        val duplicate = JsonObject(parsed + ("skills" to kotlinx.serialization.json.JsonArray(listOf(parsed.getValue("skills").jsonArray.first(), parsed.getValue("skills").jsonArray.first()))))
        assertRejected { BuiltInAgentCatalog { path -> if (path == "agent/catalog.json") ByteArrayInputStream(duplicate.toString().toByteArray()) else assets(path) }.skills }
        val unsafe = source.replace("skills/weekly-review/SKILL.md", "../weekly-review/SKILL.md")
        assertRejected { BuiltInAgentCatalog { path -> if (path == "agent/catalog.json") ByteArrayInputStream(unsafe.toByteArray()) else assets(path) }.skills }
    }

    @Test
    fun duplicateYamlKeysAndNameMismatchFailEvenWithValidHash() {
        val original = assets("agent/skills/weekly-review/SKILL.md").bufferedReader().use { it.readText() }
        listOf(
            original.replace("name: weekly-review", "name: weekly-review\nname: weekly-review"),
            original.replace("name: weekly-review", "name: wrong-name")
        ).forEach { skill ->
            val bytes = skill.toByteArray()
            val source = assets("agent/catalog.json").bufferedReader().use { it.readText() }
            val parsed = Json.parseToJsonElement(source).jsonObject
            val skills = parsed.getValue("skills").jsonArray.map { item ->
                val value = item.jsonObject
                if (value.getValue("id") == JsonPrimitive("weekly-review")) {
                    val files = value.getValue("files").jsonObject + ("SKILL.md" to JsonPrimitive(hash(bytes)))
                    JsonObject(value + ("files" to JsonObject(files)) + ("bundleHash" to JsonPrimitive(bundleHash(files))))
                } else {
                    item
                }
            }
            val rewritten = JsonObject(parsed + ("skills" to kotlinx.serialization.json.JsonArray(skills))).toString()
            val catalog = BuiltInAgentCatalog { path ->
                when (path) {
                    "agent/catalog.json" -> ByteArrayInputStream(rewritten.toByteArray())
                    "agent/skills/weekly-review/SKILL.md" -> ByteArrayInputStream(bytes)
                    else -> assets(path)
                }
            }
            assertRejected { catalog.loadSkill("weekly-review", AssistantPreferences()) }
        }
    }

    @Test
    fun allPublishedSkillsHaveValidYamlAndResources() {
        val catalog = BuiltInAgentCatalog(::assets)
        catalog.skills.forEach { skill ->
            assertTrue(catalog.loadSkill(skill.id, AssistantPreferences()).body.isNotBlank())
            skill.files.keys.filter { it != "SKILL.md" }.forEach { path ->
                assertTrue(catalog.readResource(skill.id, path, AssistantPreferences()).isNotBlank())
            }
        }
    }

    @Test
    fun identityBudgetAndResourceBudgetRejectOversizeText() {
        val catalog = BuiltInAgentCatalog(::assets)
        assertRejected { catalog.snapshot(AssistantPreferences(soul = "界".repeat(11_000))) }
        val oversized = BuiltInAgentCatalog { path ->
            if (path.endsWith("weekly-review/SKILL.md")) ByteArrayInputStream(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }) else assets(path)
        }
        assertRejected { oversized.loadSkill("weekly-review", AssistantPreferences()) }
    }

    private fun assets(path: String) = File("src/main/assets", path).inputStream()

    private fun assertRejected(block: () -> Unit) {
        assertTrue("Expected invalid configuration to be rejected", runCatching(block).isFailure)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bundleHash(files: Map<String, kotlinx.serialization.json.JsonElement>): String = hash(
        files.toSortedMap().entries.joinToString("") { (path, hash) -> "$path\u0000${(hash as JsonPrimitive).content}\n" }.toByteArray()
    )
}
