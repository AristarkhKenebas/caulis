rootProject.name = "tessera"

// Shared build settings (Java version, Kotlin, tests) live in build-logic.
pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "tessera-native",
    "examples:minimal",
)
