import org.jetbrains.dokka.DokkaConfiguration.Visibility
import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("org.jlleitschuh.gradle.ktlint")

    id("signing")
    id("maven-publish")
}

group = "com.sletmoe.korogue"
version = Ci.version

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(KotlinX.coroutines.core)
    implementation(KotlinX.datetime)
    implementation(KotlinX.serialization.cbor)
    implementation("io.github.microutils:kotlin-logging:_")
    implementation("org.slf4j:slf4j-log4j12:_")
    implementation("org.apache.commons:commons-math3:_")
    // kotile rendering engine — an in-repo Gradle subproject (see settings.gradle.kts).
    // `api` because kotile types (AsciiTileWindow, GDX Color) appear in the engine's public
    // API (e.g. Game, TileSurface), so consumers need them on their compile classpath. Brings
    // libGDX gdx-core transitively via kotile's own `api` dependency.
    api(project(":kotile:library"))

    testImplementation(kotlin("test"))
    testImplementation(Testing.kotest.runner.junit5)
    testImplementation(Testing.kotest.assertions.core)
    testImplementation(Testing.kotest.property)
}

tasks.test {
    useJUnitPlatform()
}

java {
    withJavadocJar()
    withSourcesJar()

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<DokkaTask>().configureEach {
    moduleName.set(project.name)
    moduleVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("dokka/$name"))
    failOnWarning.set(false)
    suppressObviousFunctions.set(true)
    suppressInheritedMembers.set(false)
    offlineMode.set(false)

    dokkaSourceSets {
        configureEach {
            documentedVisibilities.set(setOf(Visibility.PUBLIC))
            reportUndocumented.set(false)
            skipEmptyPackages.set(true)
            skipDeprecated.set(false)
            suppressGeneratedFiles.set(true)
            jdkVersion.set(21)
            languageVersion.set("2.3")
            apiVersion.set("2.3")
            noStdlibLink.set(false)
            noJdkLink.set(false)
            platform.set(Platform.DEFAULT)
            sourceRoots.from(file("src"))

            sourceLink {
                localDirectory.set(projectDir.resolve("src"))
                remoteUrl.set(URL("https://github.com/ksletmoe/korogue/tree/mainline/engine/src"))
                remoteLineSuffix.set("#L")
            }

            perPackageOption {
                suppress.set(false)
                skipDeprecated.set(false)
                reportUndocumented.set(false)
                documentedVisibilities.set(
                    setOf(
                        Visibility.PUBLIC,
                    ),
                )
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("korogue")
                description.set("An ASCII Roguelike development framework, written in Kotlin.")
                url.set("https://www.github.com/ksletmoe/korogue")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("ksletmoe")
                        name.set("Kyle Sletmoe")
                        email.set("kyle@sletmoe.com")
                    }
                }

                scm {
                    url.set("https://github.com/ksletmoe/korogue")
                    connection.set("scm:git://github.com/ksletmoe/korogue.git")
                    developerConnection.set("scm:git://github.com/ksletmoe/korogue")
                }
            }
        }
    }
}

val signingKey: String? by project
val signingPassword: String? by project

signing {
    useGpgCmd()

    if (signingKey != null && signingPassword != null) {
        @Suppress("UnstableApiUsage")
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    sign(publishing.publications["mavenJava"])
}
