// Rules for particular programs, and reacting to what happens.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
}

application {
    mainClass = "dev.caulis.examples.windowrules.MainKt"
}
