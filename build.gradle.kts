// Root is a pure aggregator: no production sources. Real modules are subprojects
// (:engine, :demo, :kotile:library, :kotile:demo). The nexus-publish plugin lives here
// (it is designed for an aggregator root) and gathers every subproject's Maven publication
// into a single Sonatype staging repository — so `publishToSonatype` pushes both
// com.sletmoe.korogue:korogue (:engine) and com.sletmoe:kotile (:kotile:library).
plugins {
    id("io.github.gradle-nexus.publish-plugin")
}

group = "com.sletmoe.korogue"
version = Ci.version

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}

// Share the pre-commit hook across clones: point git at the checked-in .githooks/ directory
// (git hooks in .git/hooks are not version-controlled, but core.hooksPath is redirectable).
// Idempotent and local-only; skipped when there is no .git (e.g. a source tarball).
val installGitHooks =
    tasks.register<Exec>("installGitHooks") {
        description = "Point git core.hooksPath at the checked-in .githooks directory."
        group = "git hooks"
        onlyIf { rootProject.file(".git").exists() }
        commandLine("git", "config", "core.hooksPath", ".githooks")
    }

// Make an ordinary build install the hook, so a fresh clone is wired up after the first
// ./gradlew check/build (the same commands CI and developers already run).
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(installGitHooks) }
}
