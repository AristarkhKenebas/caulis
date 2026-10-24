plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-native"))
}

application {
    mainClass = "dev.caulis.examples.minimal.MainKt"
}
