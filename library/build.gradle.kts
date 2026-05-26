plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka")
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
            }
        }
    }
}
