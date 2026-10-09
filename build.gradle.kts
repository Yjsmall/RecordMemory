plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
}

val ktlintCli by configurations.creating
val detektCli by configurations.creating
ktlintCli.attributes.attribute(
    org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
    objects.named(org.gradle.api.attributes.Bundling.SHADOWED)
)
listOf(ktlintCli, detektCli).forEach { cli ->
    cli.attributes.attribute(
        org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
        objects.named(org.gradle.api.attributes.Usage.JAVA_RUNTIME)
    )
}
dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:1.7.1")
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

tasks.register<JavaExec>("ktlint") {
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("app/src/**/*.kt", "*.gradle.kts", "app/*.gradle.kts")
}
tasks.register<JavaExec>("ktlintFormat") {
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "app/src/**/*.kt", "*.gradle.kts", "app/*.gradle.kts")
}
tasks.register<JavaExec>("detekt") {
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args("--input", "app/src/main/java", "--config", "config/detekt.yml", "--report", "txt:artifacts/detekt.txt")
}
