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
    "caulis-log",
    "caulis-native",
    "caulis-geometry",
    "caulis-tiling",
    "caulis-protocol",
    "caulis-render",
    "caulis-core",
    "caulis-xwayland",
    "caulis-ipc",
    "caulis-wm",
    "caulis-reload",
    "caulis-lua",
    "caulis-script",
    "caulis-cursor-bibata",
    "tools:cursors",
    "examples:minimal",
    "examples:minimal-java",
    "examples:custom-layout",
    "examples:window-rules",
)

