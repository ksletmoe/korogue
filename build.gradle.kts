plugins {
    kotlin("jvm") version "1.8.20"
    application
    id("org.openjfx.javafxplugin") version "0.0.14"
}

group = "com.sletmoe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val kotestVersion = "5.6.2"

dependencies {
    implementation("com.sksamuel.aedile:aedile-core:1.2.2")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

javafx {
    version = "17"
    modules("javafx.controls", "javafx.fxml")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}
