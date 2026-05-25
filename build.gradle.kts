plugins {
    kotlin("jvm") version "2.3.21"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.sletmoe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val kotestVersion = "5.6.2"

dependencies {
    implementation("com.sksamuel.aedile:aedile-core:1.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml", "javafx.swing")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}
