// Without an explicit name, Gradle derives buildSrc's project name from its containing
// directory, which shifts if the repo is checked out under a different folder name — and with
// type-safe project accessors enabled (root settings.gradle.kts), that name feeds into generated
// accessor code and the buildscript classpath, breaking caching across checkouts.
rootProject.name = "buildSrc"
