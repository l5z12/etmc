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

neoForge {
    version = property("deps.neoforge") as String
    runs {
        create("client") { client() }
    }
    mods {
        create("etmc") { sourceSet(sourceSets["main"]) }
    }
}

dependencies {
    // FFM is used on Java 21+, so JNA is compile-only here (not bundled).
    compileOnly("net.java.dev.jna:jna:${property("jna_version")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}
