// Stonecutter controller (root project). The buildable Fabric mod is each version node under
// versions/<mc>-fabric, built with build.gradle.kts. Other loaders are normal subprojects.
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.10-fabric" /* [SC] DO NOT EDIT */

// Loader constants, derived from each node's id suffix (e.g. "1.21.10-neoforge" -> neoforge). These
// drive the `//? if fabric` / `//? if neoforge` source guards so one tree serves yarn (Fabric) and
// mojmap (NeoForge/Forge). Fabric nodes get fabric=true and are unaffected until guards are added.
stonecutter.parameters {
    val loader = current.project.substringAfterLast('-')
    constants.put("fabric", loader == "fabric")
    constants.put("neoforge", loader == "neoforge")
    constants.put("forge", loader == "forge")
}

// Aggregate task that builds every Fabric version node (used by CI to "build all"):
//   ./gradlew chiseledBuild
tasks.register("chiseledBuild") {
    group = "project"
    description = "Builds every Stonecutter Fabric version node."
    dependsOn(stonecutter.tasks.named("build").map { it.values })
}

allprojects {
    group = providers.gradleProperty("maven_group").get()
    version = providers.gradleProperty("mod_version").get()
}
