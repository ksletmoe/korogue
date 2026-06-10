pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.3.21"
        kotlin("plugin.serialization") version "2.3.21"
        id("org.jetbrains.dokka") version "2.2.0"
        id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
        id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    }
}

plugins {
    id("de.fayard.refreshVersions") version "0.60.6"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

refreshVersions {
    rejectVersionIf {
        candidate.stabilityLevel != de.fayard.refreshVersions.core.StabilityLevel.Stable
    }
}

rootProject.name = "korogue"

// kotile lives in this repo as Gradle subprojects (folded in from its former sibling
// repo). It keeps its own maven coordinates (com.sletmoe:kotile via the :kotile:library
// publication); the engine depends on project(":kotile:library").
include(":kotile:library", ":kotile:demo")
