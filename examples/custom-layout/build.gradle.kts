// Your own way of placing windows, described with the layout language.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
}

application {
    mainClass = "dev.caulis.examples.customlayout.MainKt"
}
