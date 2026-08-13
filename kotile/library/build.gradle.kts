plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion: String by project
val kotestVersion: String by project

dependencies {
    // gdx core is part of the public API (Color, TextureRegion, etc. appear in
    // public signatures), so it is exposed transitively to consumers. The
    // consumer chooses its own gdx backend (lwjgl3, android, ...).
    api("com.badlogicgames.gdx:gdx:$gdxVersion")
    // gdx-freetype backs the tier-3 freetype glyph source (FreeTypeGlyphSource,
    // ADR-0036 / krogue-9x7.2). Exposed as `api` because a consumer that supplies
    // its own TTF touches FreeTypeFontGenerator's parameter types. The consumer
    // must also put the freetype *native* on its runtime classpath alongside the
    // backend natives it already provides (e.g. gdx-freetype-platform:natives-desktop).
    api("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    // The integration tests need a real backend to obtain a GL context.
    testImplementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    // Native freetype for the glyph-source tests / ssVerify-style harnesses.
    testRuntimeOnly("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

dokka {
    moduleName.set("kotile")
    dokkaSourceSets.configureEach {
        // The JDK API docs aren't reachable from this build environment.
        enableJdkDocumentationLink.set(false)
        reportUndocumented.set(true)
    }
}

// Package the generated API docs as the javadoc artifact for publishing.
val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "Packages the Dokka API documentation as the javadoc artifact."
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

tasks.test {
    useJUnitPlatform()
    // Headless GL integration tests need a display + software OpenGL. They are
    // skipped automatically when DISPLAY is absent (see HeadlessGl.available);
    // run them under e.g. `xvfb-run -a ./gradlew :library:test`.
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
    environment("GALLIUM_DRIVER", "llvmpipe")
    System.getenv("DISPLAY")?.let { environment("DISPLAY", it) }
    // Full stack traces for failures. The GL tests only ever run on CI, so its log is
    // the only place their failures can be read -- and Gradle's default output shows
    // just "GdxRuntimeException at HeadlessGl.kt:NN" with no message, which turned
    // diagnosing krogue-8lo into guesswork.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
    // Forward -Pkotile.benchmark=true to the test JVM so the benchmark spec
    // can opt in via assumeTrue. Usage:
    //   ./gradlew :library:test --tests "*.LayeredTilemapBenchmark" -Pkotile.benchmark=true
    if (project.hasProperty("kotile.benchmark")) {
        systemProperty("kotile.benchmark", project.property("kotile.benchmark").toString())
    }
}

// macOS-only manual verification for the supersample→downsample path (krogue-1zo): the Kotest GL
// specs can't run on macOS (GLFW needs the first thread), so this forks a JVM with
// -XstartOnFirstThread and runs SupersampleManualVerify.main(), which reproduces those specs' exact
// geometry/GL state and prints PASS/FAIL. Uses the test runtime classpath so it can reach the
// internal shader. On Linux/CI just run the real specs via `xvfb-run -a ./gradlew :kotile:library:test`.
tasks.register<JavaExec>("ssVerify") {
    group = "verification"
    description = "Manually verifies the supersample→downsample path on real pixels (macOS main-thread GL)."
    mainClass.set("com.sletmoe.kotile.SupersampleManualVerifyKt")
    classpath = sourceSets["test"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
}

// macOS-only manual verification for the freetype glyph source (krogue-9x7.2): renders the full CP437
// page through FreeTypeGlyphSource, dumps a PNG to eyeball glyph shape/placement, and reads pixels back
// for smoke assertions. Forks a JVM with -XstartOnFirstThread and uses the test runtime classpath (for
// the freetype native + internal helpers). On Linux/CI the real GL specs cover this.
tasks.register<JavaExec>("freetypeVerify") {
    group = "verification"
    description = "Renders the CP437 page via FreeTypeGlyphSource to a PNG and checks it (macOS main-thread GL)."
    mainClass.set("com.sletmoe.kotile.FreeTypeManualVerifyKt")
    classpath = sourceSets["test"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as? String)
            ?: layout.buildDirectory.file("freetype-verify.png").get().asFile.absolutePath
    systemProperty("kotile.ftverify.out", outFile)
    doFirst { logger.lifecycle("Rendering freetype chart to: $outFile") }
}

// macOS-only manual verification for the artist tilesheet glyph source (krogue-9x7.7): reproduces the
// committed GL specs' geometry and GL state (per-check window size, capture FBO, sample rects), prints
// PASS/FAIL, and dumps each capture as a PNG to eyeball. Forks a JVM with -XstartOnFirstThread and uses
// the test runtime classpath. On Linux/CI run the real specs via `xvfb-run -a ./gradlew :kotile:library:test`.
tasks.register<JavaExec>("tileSheetVerify") {
    group = "verification"
    description = "Verifies TileSheetGlyphSource on real pixels and dumps captures (macOS main-thread GL)."
    mainClass.set("com.sletmoe.kotile.TileSheetManualVerifyKt")
    classpath = sourceSets["test"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    val outFile =
        (project.findProperty("outFile") as? String)
            ?: layout.buildDirectory.file("tilesheet-verify.png").get().asFile.absolutePath
    systemProperty("kotile.tsverify.out", outFile)
    doFirst { logger.lifecycle("Writing tilesheet captures next to: $outFile") }
}

// macOS-only font-evaluation harness (krogue-9x7.8): renders candidate faces through FreeTypeGlyphSource
// at small cell sizes with snap on, prints crispness/weight metrics, and dumps comparison PNGs. Throwaway
// tooling — not part of the shipped suite. Fonts are read from an absolute -PfontsDir.
tasks.register<JavaExec>("fontEval") {
    group = "verification"
    description = "Evaluates candidate fonts through FreeTypeGlyphSource (macOS main-thread GL)."
    mainClass.set("com.sletmoe.kotile.FontEvalKt")
    classpath = sourceSets["test"].runtimeClasspath
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
    (project.findProperty("fontsDir") as? String)?.let { systemProperty("kotile.fonteval.fonts", it) }
    (project.findProperty("outDir") as? String)?.let { systemProperty("kotile.fonteval.out", it) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kotile"
            from(components["java"])
            artifact(dokkaJavadocJar)
            pom {
                name.set("kotile")
                description.set("A Kotlin tile-rendering library for ASCII and image sprite sheets, built on libGDX.")
                licenses {
                    license {
                        name.set("BSD 3-Clause License")
                        url.set("https://opensource.org/licenses/BSD-3-Clause")
                    }
                }
            }
        }
    }
}
