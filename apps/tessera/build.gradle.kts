// The Tessera compositor program.

plugins {
    id("tessera.application")
}

dependencies {
    implementation(project(":tessera-core"))
}

application {
    mainClass = "dev.tessera.app.MainKt"
    applicationName = "tessera"
}
