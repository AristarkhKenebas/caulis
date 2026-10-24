plugins {
    id("tessera.application")
}

dependencies {
    implementation(project(":tessera-native"))
}

application {
    mainClass = "dev.tessera.examples.minimal.MainKt"
}
