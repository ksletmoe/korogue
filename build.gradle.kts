import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
    application
}

group = "com.sletmoe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // maven("https://jitpack.io" )
    mavenLocal()
}

val kotestVersion = "5.3.1"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.microutils:kotlin-logging:2.1.23")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.2")
    implementation("org.slf4j:slf4j-log4j12:1.7.36")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.2")
    implementation("org.apache.commons:commons-math3:3.6.1")
    // implementation("com.github.trystan:AsciiPanel:master-SNAPSHOT")
    // TODO
    implementation("net.trystan:ascii-panel:1.2-SNAPSHOT")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-framework-datatest:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}
