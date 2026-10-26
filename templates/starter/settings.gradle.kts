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

// Caulis comes from Maven Central — or from its source folder, when gradle.properties names one
// (templates/new-compositor.sh --sources): changes to Caulis then show at once.
providers.gradleProperty("caulis.sources").orNull?.let { path ->
    val sources = file(path)
    check(sources.resolve("caulis-wm").isDirectory) { "caulis.sources in gradle.properties does not point to Caulis: $sources" }
    includeBuild(sources)
}
