pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.kikugie.dev/releases") { name = "Kikugie" }
        maven("https://maven.kikugie.dev/snapshots") { name = "Kikugie Snapshots" }
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.2"
    // Auto-provision JDK toolchains (17/21/25) per version node.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "etmc"

// Paper stays a (multi-version-via-CI-matrix) subproject; NeoForge + Forge are now Stonecutter
// loader nodes built from the shared src/ tree (see below), not standalone subprojects.
include("paper")

// Stonecutter versions the Fabric build (root = controller, root src/ = shared source).
stonecutter {
    create(rootProject) {
        // 26.2-fabric: unobfuscated, official names, own snapshot-Loom node script.
        version("26.2-fabric", "26.2").buildscript("build.fabric26.gradle")
        version("1.21.10-fabric", "1.21.10")
        version("1.20.6-fabric", "1.20.6")
        version("1.20.1-fabric", "1.20.1")
        version("1.19.4-fabric", "1.19.4")
        version("1.18.2-fabric", "1.18.2")
        version("1.17.1-fabric", "1.17.1")
        // Mojmap loader nodes (own buildscript): the shared root src/ is processed with fabric=false.
        version("1.21.11-neoforge", "1.21.11").buildscript("build.neoforge.gradle.kts")
        version("1.21.10-neoforge", "1.21.10").buildscript("build.neoforge.gradle.kts")
        version("1.21.8-neoforge", "1.21.8").buildscript("build.neoforge.gradle.kts")
        version("1.21.5-neoforge", "1.21.5").buildscript("build.neoforge.gradle.kts")
        version("1.21.4-neoforge", "1.21.4").buildscript("build.neoforge.gradle.kts")
        version("1.21.3-neoforge", "1.21.3").buildscript("build.neoforge.gradle.kts")
        version("1.21.1-neoforge", "1.21.1").buildscript("build.neoforge.gradle.kts")
        version("1.21-neoforge", "1.21").buildscript("build.neoforge.gradle.kts")
        version("1.20.6-neoforge", "1.20.6").buildscript("build.neoforge.gradle.kts")
        // Forge nodes span both eras: EtmcForge guards EventBus 7 (>=1.21.6) vs EventBus 6 (<1.21.6),
        // and the HUD-layer event across 1.21–1.21.5 / 1.21.6-1.21.7 (no API → no overlay) / 1.21.8+.
        version("1.21.11-forge", "1.21.11").buildscript("build.forge.gradle")
        version("1.21.10-forge", "1.21.10").buildscript("build.forge.gradle")
        version("1.21.9-forge", "1.21.9").buildscript("build.forge.gradle")
        version("1.21.8-forge", "1.21.8").buildscript("build.forge.gradle")
        version("1.21.7-forge", "1.21.7").buildscript("build.forge.gradle")
        version("1.21.6-forge", "1.21.6").buildscript("build.forge.gradle")
        version("1.21.5-forge", "1.21.5").buildscript("build.forge.gradle")
        version("1.21.4-forge", "1.21.4").buildscript("build.forge.gradle")
        version("1.21.3-forge", "1.21.3").buildscript("build.forge.gradle")
        version("1.21.1-forge", "1.21.1").buildscript("build.forge.gradle")
        version("1.21-forge", "1.21").buildscript("build.forge.gradle")
        vcsVersion = "1.21.10-fabric"
    }
}
