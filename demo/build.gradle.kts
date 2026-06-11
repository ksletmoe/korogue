// The runnable demo game (MyGame). A consumer of the :engine library — not published —
// kept separate so the engine artifact doesn't ship demo code. Run with `./gradlew :demo:run`.
plugins {
    kotlin("jvm")
    application
    id("org.jlleitschuh.gradle.ktlint")
}

group = "com.sletmoe.korogue"
version = Ci.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":engine"))
    // Direct use of kotile types (AsciiTileWindow) — also arrives transitively via :engine's
    // `api` dependency, declared here for clarity.
    implementation(project(":kotile:library"))
    // libGDX LWJGL3 desktop backend + native libraries to actually launch the window.
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.14.1")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:1.14.1:natives-desktop")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
    // -XstartOnFirstThread is required on macOS for GLFW (libGDX LWJGL3 backend).
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}
