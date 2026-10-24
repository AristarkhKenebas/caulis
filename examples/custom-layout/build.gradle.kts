// Your own way of placing windows: equal columns side by side.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
}

application {
    mainClass = "dev.caulis.examples.customlayout.MainKt"
}
