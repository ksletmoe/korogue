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

// Multi-project: the root is a pure aggregator. The korogue engine library is :engine,
// the runnable demo is :demo, and kotile (folded in from its former sibling repo) lives as
// :kotile:library (publishable, com.sletmoe:kotile) + :kotile:demo.
include(":engine", ":demo", ":kotile:library", ":kotile:demo")
