// NeoForge node buildscript (mojmap). The shared client source lives in the root src/ (Stonecutter
// processes it with fabric=false -> the //? if !fabric mojmap branches) + common/. Loader entry
// points for the OTHER loaders are excluded.
plugins {
    id("net.neoforged.moddev") version "2.0.141"
}

base { archivesName = "etmc-neoforge-${stonecutter.current.version}" }

val javaVersion = (property("java_version") as String).toInt()

repositories {
    mavenCentral()
}

sourceSets {
    named("main") {
        java {
            srcDir(rootProject.file("common/src/main/java"))
            // This is the NeoForge node: drop the Fabric (EtmcClient) + Forge entry points.
            exclude("**/EtmcClient.java", "**/forge/**")
        }
        resources {
            srcDir(rootProject.file("common/src/main/resources"))
            srcDir(rootProject.file("neoforge/src/main/resources"))
        }
    }
}

// CI's launch test (-Petmc.launchProbe=true): see gradle/launch-probe.gradle.
apply(from = rootProject.file("gradle/launch-probe.gradle"))
val launchProbeResult = extra["launchProbeResult"] as String?

neoForge {
    version = property("deps.neoforge") as String
    runs {
        create("client") {
            client()
            if (launchProbeResult != null) systemProperty("etmc.launchProbe", launchProbeResult)
        }
    }
    mods {
        create("etmc") { sourceSet(sourceSets["main"]) }
    }
}

dependencies {
    // FFM is used on Java 21+, so JNA is compile-only here (not bundled).
    compileOnly("net.java.dev.jna:jna:${property("jna_version")}")
    // Lombok generates the accessors; compile-time only, so nothing lands in the jar.
    compileOnly("org.projectlombok:lombok:${property("lombok_version")}")
    annotationProcessor("org.projectlombok:lombok:${property("lombok_version")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

tasks.processResources {
    // Mixin compatibilityLevel must not exceed the runtime Java (see fabric build); match the toolchain.
    val mixinCompat = if (javaVersion <= 17) "JAVA_17" else "JAVA_21"
    // mods.toml version ranges are per node: pin Minecraft to this node's exact version, and floor the
    // NeoForge dependency at the version this node builds against (a single hardcoded range only fit the
    // original 1.21.10 target and rejected every other node — incl. 26.2 — at load).
    val minecraftRange = "[${project.property("deps.minecraft")}]"
    val neoforgeRange = "[${project.property("deps.neoforge")},)"
    inputs.property("version", project.version)
    inputs.property("compatibility_level", mixinCompat)
    inputs.property("minecraft_range", minecraftRange)
    inputs.property("neoforge_range", neoforgeRange)
    // NeoForge read META-INF/mods.toml until 20.5 renamed it to neoforge.mods.toml; under the new name
    // the 1.20.4 loader never saw etmc at all (CI's launch test caught it). The contents are the same.
    val tomlName = if ((property("deps.neoforge") as String).startsWith("20.4.")) "mods.toml" else "neoforge.mods.toml"
    inputs.property("toml_name", tomlName)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version, "minecraft_range" to minecraftRange,
                "neoforge_range" to neoforgeRange)
        name = tomlName
    }
    filesMatching("etmc.mixins.json") {
        expand("compatibility_level" to mixinCompat)
    }
}
