// Stonecutter controller (root project). The buildable Fabric mod is each version node under
// versions/<mc>-fabric, built with build.gradle.kts. Other loaders are normal subprojects.
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.10-fabric" /* [SC] DO NOT EDIT */

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
