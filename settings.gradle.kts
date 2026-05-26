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

rootProject.name = "kotile"

include(":library", ":demo")
