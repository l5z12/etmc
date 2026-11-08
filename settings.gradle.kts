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

// Paper is its own standalone build (paper/settings.gradle) so a server-only build never pulls in the
// Stonecutter version tree — build it with `gradle -p paper`. common/ is standalone too, for the core
// tests (`gradle -p common test`). NeoForge + Forge are Stonecutter loader
// nodes built from the shared mod/src/ tree (below), not standalone subprojects.

// The node Stonecutter keeps active (see `vcsVersion` below and mod/stonecutter.gradle.kts).
val vcsNode = "1.21.10-fabric"

// Optional node filter, e.g. `-Petmc.nodes=1\.21.*-forge,.*-neoforge` (comma-separated regexes over node
// names). CI shards the build with it: a node left out isn't registered at all, so it is never
// configured. Configuration is the expensive part (every Forge node runs the Mavenizer there), so this
// is what makes a shard cheap. The active node is always kept, because Stonecutter needs it to exist;
// chiseledBuild applies the same filter, so it doesn't build that node unless it matches.
val nodeFilter: List<Regex>? = providers.gradleProperty("etmc.nodes").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(::Regex)

fun wanted(name: String) = nodeFilter == null || name == vcsNode || nodeFilter.any { it.matches(name) }

// Each node builds with its loader's script unless it names its own. Paths are relative to mod/.
val nodeScripts = mapOf(
    "fabric" to "nodes/fabric.gradle.kts",
    "neoforge" to "nodes/neoforge.gradle.kts",
    "forge" to "nodes/forge.gradle",
)

// Stonecutter versions the client mod (mod/ = controller, mod/src/ = shared source).
stonecutter {
    create("mod") {
        fun node(name: String, mc: String, buildscript: String = nodeScripts.getValue(name.substringAfterLast('-'))) {
            if (wanted(name)) version(name, mc).buildscript(buildscript)
        }

        // 26.2-fabric: unobfuscated, official names, own snapshot-Loom node script.
        node("26.2-fabric", "26.2", "nodes/fabric26.gradle")
        node("1.21.11-fabric", "1.21.11")
        node("1.21.10-fabric", "1.21.10")
        node("1.21.9-fabric", "1.21.9")
        node("1.21.8-fabric", "1.21.8")
        node("1.21.7-fabric", "1.21.7")
        node("1.21.6-fabric", "1.21.6")
        node("1.21.5-fabric", "1.21.5")
        node("1.21.4-fabric", "1.21.4")
        node("1.21.3-fabric", "1.21.3")
        node("1.21.2-fabric", "1.21.2")
        node("1.21.1-fabric", "1.21.1")
        node("1.21-fabric", "1.21")
        node("1.20.6-fabric", "1.20.6")
        node("1.20.5-fabric", "1.20.5")
        node("1.20.4-fabric", "1.20.4")
        node("1.20.3-fabric", "1.20.3")
        node("1.20.2-fabric", "1.20.2")
        node("1.20.1-fabric", "1.20.1")
        node("1.20-fabric", "1.20")
        node("1.19.4-fabric", "1.19.4")
        node("1.19.3-fabric", "1.19.3")
        node("1.19.2-fabric", "1.19.2")
        node("1.19.1-fabric", "1.19.1")
        node("1.19-fabric", "1.19")
        node("1.18.2-fabric", "1.18.2")
        node("1.18.1-fabric", "1.18.1")
        node("1.18-fabric", "1.18")
        node("1.17.1-fabric", "1.17.1")
        // Legacy line below 1.17 (yarn + Loom reach to 1.14). 1.16.x still fits the post-MatrixStack /
        // Text-based GUI tiers; 1.15/1.14 predate MatrixStack + Text-based widgets, so the facades carry
        // a separate `<1.16` tier (String-typed buttons/labels, single-arg HudRenderCallback, no OrderedText).
        node("1.16.5-fabric", "1.16.5")
        node("1.16.4-fabric", "1.16.4")
        node("1.16.3-fabric", "1.16.3")
        node("1.16.2-fabric", "1.16.2")
        node("1.16.1-fabric", "1.16.1")
        node("1.16-fabric", "1.16")
        node("1.15.2-fabric", "1.15.2")
        node("1.15-fabric", "1.15")
        node("1.14.4-fabric", "1.14.4")
        // Mojmap loader nodes (own buildscript): the shared mod/src/ is processed with fabric=false.
        node("26.2-neoforge", "26.2")
        node("1.21.11-neoforge", "1.21.11")
        node("1.21.10-neoforge", "1.21.10")
        node("1.21.9-neoforge", "1.21.9")
        node("1.21.8-neoforge", "1.21.8")
        node("1.21.7-neoforge", "1.21.7")
        node("1.21.6-neoforge", "1.21.6")
        node("1.21.5-neoforge", "1.21.5")
        node("1.21.4-neoforge", "1.21.4")
        node("1.21.3-neoforge", "1.21.3")
        node("1.21.2-neoforge", "1.21.2")
        node("1.21.1-neoforge", "1.21.1")
        node("1.21-neoforge", "1.21")
        node("1.20.6-neoforge", "1.20.6")
        // 1.20.4 is the oldest buildable NeoForge: 1.20.2/1.20.3/1.20.5 only got beta NeoForge builds,
        // which don't publish the `neoforge-moddev-bundle` capability variant ModDevGradle 2.x requires.
        // The 1.21.2/1.21.6/1.21.7/1.21.9 nodes DO use beta builds (those carry the variant).
        node("1.20.4-neoforge", "1.20.4")
        // Forge nodes span both eras: EtmcForge guards EventBus 7 (>=1.21.6) vs EventBus 6 (<1.21.6),
        // and the HUD-layer event across 1.21–1.21.5 / 1.21.6-1.21.7 (no API → no overlay) / 1.21.8+.
        node("1.21.11-forge", "1.21.11")
        node("1.21.10-forge", "1.21.10")
        node("1.21.9-forge", "1.21.9")
        node("1.21.8-forge", "1.21.8")
        node("1.21.7-forge", "1.21.7")
        node("1.21.6-forge", "1.21.6")
        node("1.21.5-forge", "1.21.5")
        node("1.21.4-forge", "1.21.4")
        node("1.21.3-forge", "1.21.3")
        node("1.21.1-forge", "1.21.1")
        node("1.21-forge", "1.21")
        node("1.20.6-forge", "1.20.6")
        node("1.20.4-forge", "1.20.4")
        node("1.20.3-forge", "1.20.3")
        node("1.20.2-forge", "1.20.2")
        node("1.20.1-forge", "1.20.1")
        node("1.20-forge", "1.20")
        node("1.19.4-forge", "1.19.4")
        node("1.19.3-forge", "1.19.3")
        node("1.19.2-forge", "1.19.2")
        node("1.19.1-forge", "1.19.1")
        node("1.19-forge", "1.19")
        node("1.18.2-forge", "1.18.2")
        node("1.18.1-forge", "1.18.1")
        node("1.18-forge", "1.18")
        node("1.17.1-forge", "1.17.1")
        vcsVersion = vcsNode
    }
}
