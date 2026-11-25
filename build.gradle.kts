import org.gradle.jvm.toolchain.JavaLanguageVersion

val modId = project.property("mod_id") as String
val modName = project.property("mod_name") as String
val modVersion = project.property("mod_version") as String
val modGroup = project.property("mod_group") as String

val ver = stonecutter.node.metadata.version

version = "$modVersion+$ver"
group = modGroup

plugins {
    kotlin("jvm") version "2.4.10"
    alias(libs.plugins.loom)
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.kikugie.dev/releases")
    maven("https://maven.kikugie.dev/snapshots")
    maven("https://maven.teamresourceful.com/repository/maven-public/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    implementation(libs.fabric.loader)
    implementation("net.fabricmc:fabric-language-kotlin:+")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc.fabric-api:fabric-command-api-v2:${property("fabric_api_version")}")
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25

    withSourcesJar()

    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    val fabricApiRequirement = if (ver == "26.3") ">=0.166.0" else "*"
    inputs.property("version", modVersion)
    inputs.property("modid", modId)
    inputs.property("name", modName)
    inputs.property("minecraft", ver)
    inputs.property("fabricApiRequirement", fabricApiRequirement)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersion,
            "modid" to modId,
            "name" to modName,
            "minecraft" to ver,
            "fabricApiRequirement" to fabricApiRequirement
        )
    }
}

base {
    archivesName.set(modId)
}