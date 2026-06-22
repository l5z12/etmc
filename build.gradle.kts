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
        // Loader entry points (EtmcKey/EtmcNeoForge/EtmcForge) live in the shared tree but use mojmap
        // / loader APIs — they compile only on their own loader node, not in the Fabric build.
        java.exclude("**/EtmcKey.java", "**/neoforge/**", "**/forge/**")
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
    // Per-node MC range: each version node declares its own ~<mc> so the jar only loads where it fits.
    val minecraftDependency = "~${project.property("deps.minecraft")}"
    // Mixin compatibilityLevel must not exceed the runtime Java (mixins compile to options.release =
    // java_version, and MC's Java tracks it): JAVA_21 here would make Mixin reject the config on the
    // java-17 (MC <=1.20.4) runtimes. Match it to the toolchain instead.
    val mixinCompat = if (javaVersion <= 17) "JAVA_17" else "JAVA_21"
    // The Fabric API umbrella mod's id is "fabric" pre-rename and "fabric-api" after; the renamed jars
    // still `provides` "fabric", EXCEPT 26.x which dropped that alias. Every node on this buildscript is
    // <26, so depending on "fabric" covers them all (26.2 uses build.fabric26.gradle → "fabric-api").
    val fabricApiId = "fabric"
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", minecraftDependency)
    inputs.property("compatibility_level", mixinCompat)
    inputs.property("fabric_api_id", fabricApiId)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_dependency" to minecraftDependency,
                "fabric_api_id" to fabricApiId)
    }
    filesMatching("etmc.mixins.json") {
        expand("compatibility_level" to mixinCompat)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}
