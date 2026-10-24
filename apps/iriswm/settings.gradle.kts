rootProject.name = "iriswm"

plugins {
    // Downloads the right JDK automatically if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// While irisWM lives inside the Caulis repository, build against the Caulis sources next
// to it instead of a published version (Gradle swaps dev.caulis:* for these projects).
// Once irisWM has its own repository, this block simply finds nothing and is skipped.
val caulisSources = file("../..")
if (caulisSources.resolve("caulis-core").isDirectory) {
    includeBuild(caulisSources)
}
