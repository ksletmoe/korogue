plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "com.sletmoe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val gdxVersion = "1.14.1"
val kotestVersion = "6.1.11"

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
    // Headless GL integration tests need a display + software OpenGL. They are
    // skipped automatically when DISPLAY is absent (see HeadlessGl.available);
    // run them under e.g. `xvfb-run --auto-servernum ./gradlew test`.
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
    environment("GALLIUM_DRIVER", "llvmpipe")
    System.getenv("DISPLAY")?.let { environment("DISPLAY", it) }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}
