// A jar plugin. The compositor brings Caulis and Kotlin: they are compileOnly, not in the jar.
plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
    maven("https://aristarkhkenebas.github.io/caulis/maven")
}

dependencies {
    compileOnly("io.github.aristarkhkenebas:caulis-wm:0.1.9")
}
