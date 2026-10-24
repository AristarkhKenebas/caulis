rootProject.name = "caulis"

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
    "caulis-native",
    "caulis-protocol",
    "caulis-render",
    "caulis-core",
    "examples:minimal",
    "apps:iriswm",
)
