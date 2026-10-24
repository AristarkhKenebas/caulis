rootProject.name = "my-compositor"

plugins {
    // Downloads the right JDK automatically if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Caulis is used from its source folder (set in gradle.properties) until it is published.
providers.gradleProperty("caulis.sources").orNull?.let { path ->
    val sources = file(path)
    check(sources.resolve("caulis-wm").isDirectory) { "caulis.sources in gradle.properties does not point to Caulis: $sources" }
    includeBuild(sources)
}
