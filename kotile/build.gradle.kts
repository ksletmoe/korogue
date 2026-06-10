plugins {
    // Declared here (without applying) so the Kotlin and Dokka plugins are
    // loaded once on the shared classpath; subprojects apply them as needed.
    kotlin("jvm") apply false
    id("org.jetbrains.dokka") apply false
}

allprojects {
    group = "com.sletmoe"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
