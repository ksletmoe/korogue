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

// Downloads the DawnLike tileset (CC-BY 4.0, see demo/assets/dawnlike/ATTRIBUTION.md) for the
// animation showcase (krogue-aqo). Not vendored in git — run this once before building a scene
// that reads from demo/assets/dawnlike/; safe to re-run (skips if already fetched).
//
//   ./gradlew :demo:fetchDawnlikeAssets
//   ./gradlew :demo:fetchDawnlikeAssets -Pforce   # re-download and re-extract
tasks.register<Exec>("fetchDawnlikeAssets") {
    group = "assets"
    description = "Downloads the DawnLike tileset into demo/assets/dawnlike/ (CC-BY 4.0, not vendored)."
    workingDir = rootDir
    val args = mutableListOf("demo/scripts/fetch-dawnlike.sh")
    if (project.hasProperty("force")) args.add("--force")
    commandLine(args)
}

// Standalone showcase for animation work (krogue-aqo): a split sprite/glyph room, following
// kotile:demo's *Harness convention (a JavaExec task dumps a PNG via the kotile.harness.out
// system property, so the scene can be inspected without an interactive window). Requires
// demo/assets/dawnlike/ — run fetchDawnlikeAssets first.
//
//   ./gradlew :demo:animationShowcaseHarness                     # -> demo/build/animation-showcase.png
//   ./gradlew :demo:animationShowcaseHarness -PoutFile=/tmp/a.png
tasks.register<JavaExec>("animationShowcaseHarness") {
    group = "verification"
    description = "Renders the split sprite/glyph animation showcase room to a PNG."
    mainClass.set("AnimationShowcaseHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("animation-showcase.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    systemProperty("korogue.demo.assetsDir", "demo/assets/dawnlike")
    doFirst { logger.lifecycle("Rendering animation showcase scene to: $outFile") }
}

// Same scene, live and interactive: no kotile.harness.out means no auto-snapshot-and-exit, so
// the window stays open (animated at real speed) until you close it. Requires
// demo/assets/dawnlike/ — run fetchDawnlikeAssets first.
//
//   ./gradlew :demo:runAnimationShowcase
tasks.register<JavaExec>("runAnimationShowcase") {
    group = "application"
    description = "Runs the split sprite/glyph animation showcase room live, until the window is closed."
    mainClass.set("AnimationShowcaseHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    systemProperty("korogue.demo.assetsDir", "demo/assets/dawnlike")
}
