import java.nio.ByteBuffer
import java.security.MessageDigest

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:2.3") }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    jacoco
}

val validateAgentCatalog by tasks.registering {
    val root = layout.projectDirectory.dir("src/main/assets/agent")
    inputs.dir(root)
    doLast {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun safePath(path: String) {
            require(path.matches(Regex("[A-Za-z0-9_./-]+")) && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe agent path: $path" }
        }
        val folder = root.asFile
        val catalog = groovy.json.JsonSlurper().parse(folder.resolve("catalog.json")) as Map<*, *>
        require(catalog["schemaVersion"] == 1)
        listOf("SOUL.md" to "soulHash", "AGENTS.md" to "conventionsHash").forEach { (path, key) ->
            val bytes = folder.resolve(path).readBytes()
            require(bytes.size <= 32 * 1024 && hash(bytes) == catalog[key]) { "Invalid identity resource: $path" }
        }
        val skills = catalog["skills"] as List<*>
        require(skills.size <= 200)
        val ids = mutableSetOf<String>()
        val expected = mutableSetOf("catalog.json", "SOUL.md", "AGENTS.md")
        val tools = setOf("get_personal_profile", "search_memories", "get_memory_sources", "search_local_sources", "load_skill", "read_skill_resource")
        skills.forEach { entry ->
            val skill = entry as Map<*, *>
            val id = skill["id"] as String
            require(id.length <= 64 && id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && ids.add(id)) { "Invalid or duplicate skill ID: $id" }
            require(skill["path"] == "skills/$id/SKILL.md")
            val version = skill["version"] as String
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val files = (skill["files"] as Map<*, *>).entries.associate { it.key as String to it.value as String }
            require(files.size in 1..100 && "SKILL.md" in files)
            val manifest = files.toSortedMap().entries.joinToString("") { (path, digest) -> "$path\u0000$digest\n" }
            require(hash(manifest.toByteArray(Charsets.UTF_8)) == skill["bundleHash"]) { "Invalid bundle hash: $id" }
            val requiredTools = skill["requiredTools"] as List<*>
            require(requiredTools.distinct().size == requiredTools.size && requiredTools.all { it in tools })
            val links = mutableMapOf<String, List<String>>()
            files.forEach { (path, digest) ->
                safePath(path)
                require(path == "SKILL.md" || (path.startsWith("references/") && path.endsWith(".md")))
                val assetPath = "skills/$id/$path"
                expected += assetPath
                val bytes = folder.resolve(assetPath).readBytes()
                require(bytes.size <= if (path == "SKILL.md") 64 * 1024 else 128 * 1024)
                require(hash(bytes) == digest) { "Agent resource hash mismatch: $assetPath" }
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                links[path] = Regex("\\[[^]]*]\\(([^)]+)\\)").findAll(text).map { it.groupValues[1] }.filter { !it.startsWith("https://") }.map { target ->
                    safePath(target)
                    require(target in files) { "Missing reference: $id/$target" }
                    target
                }.toList()
            }
            fun visit(path: String, ancestors: Set<String>) {
                require(path !in ancestors) { "Circular skill reference: $id/$path" }
                links[path].orEmpty().forEach { visit(it, ancestors + path) }
            }
            files.keys.forEach { visit(it, emptySet()) }
            val source = folder.resolve("skills/$id/SKILL.md").readLines(Charsets.UTF_8)
            require(source.firstOrNull() == "---")
            val end = source.indices.drop(1).firstOrNull { source[it] == "---" } ?: error("Unclosed skill frontmatter: $id")
            val options = org.yaml.snakeyaml.LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                codePointLimit = 64 * 1024
                nestingDepthLimit = 10
            }
            val yaml = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(options)).load<Any>(source.subList(1, end).joinToString("\n")) as Map<*, *>
            require(yaml["name"] == id && yaml["description"] == skill["description"])
            require((yaml["description"] as String).length in 1..1024)
            require((yaml["metadata"] as Map<*, *>)["version"] == version)
            val allowed = when (val value = yaml["allowed-tools"]) {
                null -> emptyList()
                is String -> value.split(' ').filter(String::isNotBlank)
                is List<*> -> value
                else -> error("Invalid allowed-tools: $id")
            }
            require(allowed.toSet() == requiredTools.toSet())
            require(source.drop(end + 1).any(String::isNotBlank))
        }
        val actual = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet()
        require(actual == expected) { "Agent assets and catalog differ: ${actual - expected} / ${expected - actual}" }
    }
}
tasks.named("preBuild") { dependsOn(validateAgentCatalog) }

android {
    namespace = "dev.local.record"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.record"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.4.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation3:navigation3-runtime:1.0.0")
    implementation("androidx.navigation3:navigation3-ui:1.0.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.3.0-alpha02")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.datastore:datastore-core:1.2.0")
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-compiler:2.57.2")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.2")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.57.2")
}
