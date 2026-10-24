// The irisWM compositor program.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-core"))
}

application {
    mainClass = "dev.iriswm.MainKt"
    applicationName = "iriswm"
}
