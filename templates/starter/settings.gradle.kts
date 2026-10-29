rootProject.name = "my-compositor"

plugins {
    // Downloads the right JDK automatically if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Caulis' own Maven repository on GitHub Pages: the same releases (same coordinates and
        // signatures), for a version not on Central yet when Central's free monthly limit is
        // used up. Only Caulis is looked up there.
        maven("https://aristarkhkenebas.github.io/caulis/maven") {
            content { includeGroup("io.github.aristarkhkenebas") }
        }
    }
}

// Caulis comes from Maven Central (or the mirror above) — or from its source folder, when gradle.properties names one
// (templates/new-compositor.sh --sources): changes to Caulis then show at once.
providers.gradleProperty("caulis.sources").orNull?.let { path ->
    val sources = file(path)
    check(sources.resolve("caulis-wm").isDirectory) { "caulis.sources in gradle.properties does not point to Caulis: $sources" }
    includeBuild(sources)
}
