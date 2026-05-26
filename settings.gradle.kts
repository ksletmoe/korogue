pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.3.21"
    }
}

rootProject.name = "kotile"

include(":library", ":demo")
