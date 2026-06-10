pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.3.21"
        id("org.jetbrains.dokka") version "2.2.0"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotile"

include(":library", ":demo")

// ---------------------------------------------------------------------------
// Composite-build / includeBuild note
// ---------------------------------------------------------------------------
// This is a container project: the publishable artifact lives in the
// ":library" sub-project (group "com.sletmoe", artifact "kotile").
//
// If you consume kotile via Gradle composite builds (includeBuild) rather than
// via a published Maven coordinate, you MUST add a dependencySubstitution block
// in your consumer's settings.gradle.kts to route the Maven coordinate to the
// correct sub-project:
//
//   includeBuild("../kotile") {
//       dependencySubstitution {
//           substitute(module("com.sletmoe:kotile")).using(project(":library"))
//       }
//   }
//
// Without this block Gradle will report "No variants exist" because the root
// project itself has no published variants — only ":library" does.
// See the README for the full usage example.
// ---------------------------------------------------------------------------
