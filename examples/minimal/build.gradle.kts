// A complete compositor in a few lines of Kotlin (see src/main/kotlin).

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
}

application {
    mainClass = "dev.caulis.examples.minimal.MainKt"
}
