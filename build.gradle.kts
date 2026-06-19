// Node buildscript: applied to each Stonecutter version node (versions/<mc>-fabric). The shared mod
// source lives in the root src/ (processed by Stonecutter per version) + common/ (FFI + networking).
plugins {
    id("fabric-loom") version "1.17.11"
}

base { archivesName = "etmc-fabric-${stonecutter.current.version}" }

repositories {
    mavenCentral()
}

// Shared platform-independent sources (FFI + networking) + bundled natives.
sourceSets {
    named("main") {
        java.srcDir(rootProject.file("common/src/main/java"))
        resources.srcDir(rootProject.file("common/src/main/resources"))
    }
}

loom {
    runs {
        named("client") {
            client()
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    mappings("net.fabricmc:yarn:${property("deps.yarn")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    // JNA backend (Java 17 fallback); compileOnly on Java-21 versions where FFM is used.
    compileOnly("net.java.dev.jna:jna:${property("jna_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
