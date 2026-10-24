rootProject.name = "caulis"

// Shared build settings (Java version, Kotlin, tests) live in build-logic.
pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the right JDK automatically if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "caulis-native",
    "caulis-geometry",
    "caulis-tiling",
    "caulis-protocol",
    "caulis-render",
    "caulis-core",
    "caulis-wm",
    "examples:minimal",
    "examples:minimal-java",
    "examples:custom-layout",
    "examples:window-rules",
)

