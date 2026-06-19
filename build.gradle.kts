// Node buildscript: applied to each Stonecutter version node (versions/<mc>-fabric). The shared mod
// source lives in the root src/ (processed by Stonecutter per version) + common/ (FFI + networking).
plugins {
    id("fabric-loom") version "1.17.11"
}

base { archivesName = "etmc-fabric-${stonecutter.current.version}" }

// Java per MC version: 17 for 1.17.1-1.20.4, 21 for 1.20.5+/1.21.x, 25 for 26.x.
val javaVersion = (property("java_version") as String).toInt()

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
    // JNA is the FFI fallback for Java 17 (no java.lang.foreign): bundle it via Loom JiJ on those
    // versions so the runtime has it; on Java 19+ FFM is used so it's compileOnly (not bundled).
    if (javaVersion >= 19) {
        compileOnly("net.java.dev.jna:jna:${property("jna_version")}")
    } else {
        implementation("net.java.dev.jna:jna:${property("jna_version")}")
        include("net.java.dev.jna:jna:${property("jna_version")}")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}
