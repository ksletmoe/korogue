plugins {
    kotlin("jvm")
    application
}

val gdxVersion: String by project

dependencies {
    implementation(project(":library"))
    // gdx core arrives transitively from :library; the app supplies the
    // desktop backend and its native libraries.
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
    applicationName = "kotile"
}
