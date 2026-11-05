pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Not "freer": the root project and the library subproject would then share the coordinates
// com.benjaminearley:freer, and Gradle would resolve a dependency on the library, such as the
// Dokka aggregation's, to the root project instead.
rootProject.name = "Freer"
include(":freer", ":freer-trace", ":examples", ":jcstress")
