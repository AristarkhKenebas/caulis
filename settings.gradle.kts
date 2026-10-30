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
        // Compose's androidx parts (caulis-compose) are on Google's repository.
        google()
    }
}

include(
    "caulis-log",
    "caulis-native",
    "caulis-dbus",
    "caulis-geometry",
    "caulis-tiling",
    "caulis-protocol",
    "caulis-render",
    "caulis-core",
    "caulis-xwayland",
    "caulis-ipc",
    "caulis-wm",
    "caulis-screenshot",
    "caulis-compose",
    "caulis-reload",
    "caulis-lua",
    "caulis-script",
    "tools:cursors",
    "examples:minimal",
    "examples:minimal-java",
    "examples:custom-layout",
    "examples:window-rules",
    "examples:desktop",
)

