plugins {
    kotlin("jvm")
    application
}

val gdxVersion: String by project

dependencies {
    implementation(project(":kotile:library"))
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
    // LWJGL3/GLFW must be initialized on the process's first thread on macOS.
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

// Standalone visual render harness. Draws a controlled ASCII scene through the
// same AsciiTileWindow viewport path krogue uses and dumps a PNG, so rendering
// can be inspected on macOS (where the JUnit GL tests can't run — GLFW needs
// the first thread, which Gradle test workers don't own). A JavaExec task forks
// its own JVM, so -XstartOnFirstThread takes effect on that JVM's main thread.
//
//   ./gradlew :demo:renderHarness                     # -> demo/build/harness.png
//   ./gradlew :demo:renderHarness -PoutFile=/tmp/h.png
tasks.register<JavaExec>("renderHarness") {
    group = "verification"
    description = "Renders a controlled ASCII scene to a PNG (override path with -PoutFile=<path>)."
    mainClass.set("RenderHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile = (project.findProperty("outFile") as String?)
        ?: layout.buildDirectory.file("harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering harness scene to: $outFile") }
}

// Sprite-path counterpart to renderHarness: draws the bundled image sheet via
// SpriteTileRenderer and dumps a PNG. Doubles as a regression check for the
// macOS NPOT-atlas fix on the sprite path.
//
//   ./gradlew :demo:spriteHarness                          # -> demo/build/sprite-harness.png
//   ./gradlew :demo:spriteHarness -PoutFile=/tmp/s.png
tasks.register<JavaExec>("spriteHarness") {
    group = "verification"
    description = "Renders a controlled sprite-sheet scene to a PNG (override path with -PoutFile=<path>)."
    mainClass.set("SpriteRenderHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile = (project.findProperty("outFile") as String?)
        ?: layout.buildDirectory.file("sprite-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering sprite harness scene to: $outFile") }
}
