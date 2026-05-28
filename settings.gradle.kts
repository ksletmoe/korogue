pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.3.21"
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

rootProject.name = "krogue"

// Consume kotile via composite build during co-development (kotile is unpublished).
// kotile is multi-module (its root is a container with no consumable variants), so
// the substitution must target the :library subproject explicitly — otherwise
// resolution fails with "No variants exist". See kotile's settings.gradle.kts.
includeBuild("../kotile") {
    dependencySubstitution {
        substitute(module("com.sletmoe:kotile")).using(project(":library"))
    }
}
