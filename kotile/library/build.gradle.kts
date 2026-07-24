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

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    // The integration tests need a real backend to obtain a GL context.
    testImplementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
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
