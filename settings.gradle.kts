pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets a fresh clone build without a locally installed JDK 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "hauntedjvm"

include("core", "persistence", "diagnostics", "audio", "app", "benchmark")
