// The same compositor as examples/minimal, written in Java.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
}

application {
    mainClass = "dev.caulis.examples.minimaljava.Main"
}
