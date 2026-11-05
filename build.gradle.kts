import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

group = "com.github.uni0305"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.skriptlang.org/releases")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.skript)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
}

kotlin {
    jvmToolchain(25)
}

paperPluginYaml {
    main = "com.github.uni0305.skommand.SkommandPlugin"
    apiVersion = libs.versions.minecraft
    authors = listOf("Uni0305")
    dependencies {
        server("Skript", PaperPluginYaml.Load.BEFORE)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier = ""
}

tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
    downloadPlugins {
        modrinth("skript", libs.versions.skript.get())
    }
}
