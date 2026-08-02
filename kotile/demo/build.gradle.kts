plugins {
    kotlin("jvm")
    application
    id("org.jlleitschuh.gradle.ktlint")
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
    val outFile =
        (project.findProperty("outFile") as String?)
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
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("sprite-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering sprite harness scene to: $outFile") }
}

// Fixed-grid counterpart to renderHarness (krogue-3eu): draws a full-block ring
// through the scaling/centering/hard-clip path the reflow demo can't reach, and
// dumps a PNG. All knobs are overridable so several cases snapshot from one box:
//
//   ./gradlew :kotile:demo:fixedGridHarness -PoutFile=/tmp/c.png \
//       -PwinW=850 -PwinH=430 -Pcols=40 -Prows=20 -Ppolicy=integer
tasks.register<JavaExec>("fixedGridHarness") {
    group = "verification"
    description = "Renders a fixed-grid scene (scaling/centering/hard-clip) to a PNG."
    mainClass.set("FixedGridHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("fixed-grid-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    (project.findProperty("winW") as String?)?.let { systemProperty("kotile.harness.winW", it) }
    (project.findProperty("winH") as String?)?.let { systemProperty("kotile.harness.winH", it) }
    (project.findProperty("cols") as String?)?.let { systemProperty("kotile.harness.cols", it) }
    (project.findProperty("rows") as String?)?.let { systemProperty("kotile.harness.rows", it) }
    (project.findProperty("policy") as String?)?.let { systemProperty("kotile.harness.policy", it) }
    doFirst { logger.lifecycle("Rendering fixed-grid harness scene to: $outFile") }
}

// Font sample (krogue-kotile-font12): renders a bundled font sheet through the
// real Font + AsciiTileWindow path (full CP437 chart + sample text + box frame)
// and dumps a PNG, so a bundled sheet can be eyeballed exactly as consumers get it.
//
//   ./gradlew :kotile:demo:fontHarness -PoutFile=/tmp/f.png -Pfont=12x12
tasks.register<JavaExec>("fontHarness") {
    group = "verification"
    description = "Renders a bundled CP437 font sheet (chart + text + box frame) to a PNG."
    mainClass.set("FontSampleHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("font-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    (project.findProperty("font") as String?)?.let { systemProperty("kotile.harness.font", it) }
    doFirst { logger.lifecycle("Rendering font sample harness to: $outFile") }
}

// Free-layer counterpart (krogue-tk9, ADR-0018): draws an EffectsLayer of bolts
// at sub-tile pixel positions composited over an ASCII grid, and dumps a PNG.
//
//   ./gradlew :kotile:demo:effectsHarness                     # -> demo/build/effects-harness.png
//   ./gradlew :kotile:demo:effectsHarness -PoutFile=/tmp/e.png
tasks.register<JavaExec>("effectsHarness") {
    group = "verification"
    description = "Renders the free effects layer (sub-tile bolts over a grid) to a PNG."
    mainClass.set("EffectsHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("effects-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering effects harness scene to: $outFile") }
}

// Free (pixel-space) UI counterpart (krogue-tvm, ADR-0018): draws a UiLayer of
// pixel-positioned widgets over an ASCII grid, drives a synthetic hover through
// pixel-space hit-testing (the hovered button lights up), and dumps a PNG.
//
//   ./gradlew :kotile:demo:uiHarness                     # -> demo/build/ui-harness.png
//   ./gradlew :kotile:demo:uiHarness -PoutFile=/tmp/ui.png
tasks.register<JavaExec>("uiHarness") {
    group = "verification"
    description = "Renders the free UI layer (pixel-space widgets + hover hit-testing over a grid) to a PNG."
    mainClass.set("UiHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("ui-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering UI harness scene to: $outFile") }
}

// Artist-tilesheet showcase (krogue-9x7.7, ADR-0042): draws a synthetic 128px-per-tile "artist" sheet,
// resolves it through TileSheetGlyphSource at many cell sizes / inks / options, and dumps a labelled PNG
// chart of true 1:1 output pixels.
//
//   ./gradlew :kotile:demo:tileSheetHarness                     # -> demo/build/tilesheet-harness.png
//   ./gradlew :kotile:demo:tileSheetHarness -PoutFile=/tmp/t.png
tasks.register<JavaExec>("tileSheetHarness") {
    group = "verification"
    description = "Renders the hi-res tilesheet glyph source (cell sizes, inks, snap, aspect) to a PNG."
    mainClass.set("TileSheetHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("tilesheet-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering tilesheet showcase to: $outFile") }
}

// Live counterpart to tileSheetHarness (krogue-9x7.7, ADR-0042): a resizable window whose map is drawn
// from the hi-res synthetic sheet through a resolutionIndependent AsciiTileWindow, so every resize
// re-resolves the 128px masters at the new cell px. SPACE toggles snapToPixelGrid, C toggles the ink.
//
//   ./gradlew :kotile:demo:tileSheetDemo
//   ./gradlew :kotile:demo:tileSheetDemo -Psnapshot=/tmp/live.png   # render 3 frames, dump, exit
tasks.register<JavaExec>("tileSheetDemo") {
    group = "application"
    description = "Runs the live, resizable hi-res tilesheet map (resolution-independent re-rasterise)."
    mainClass.set("TileSheetLiveDemoKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    (project.findProperty("snapshot") as String?)?.let { systemProperty("kotile.demo.snapshot", it) }
}

// Empirical check for krogue-m05 (drawSprite rotation): four quadrant-colored copies at
// 0/90/180/270 degrees, to eyeball the rotation direction/sign convention (this machine has no
// DISPLAY, so the headless-GL integration tests that assert on this are skipped locally).
//
//   ./gradlew :kotile:demo:rotationHarness                     # -> demo/build/rotation-harness.png
//   ./gradlew :kotile:demo:rotationHarness -PoutFile=/tmp/r.png
tasks.register<JavaExec>("rotationHarness") {
    group = "verification"
    description = "Renders a sprite at 0/90/180/270 degrees to a PNG, to check the rotation direction by eye."
    mainClass.set("RotationHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as String?)
            ?: layout.buildDirectory.file("rotation-harness.png").get().asFile.absolutePath
    systemProperty("kotile.harness.out", outFile)
    doFirst { logger.lifecycle("Rendering rotation harness scene to: $outFile") }
}
