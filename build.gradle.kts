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
