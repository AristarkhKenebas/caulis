// A jar plugin. The compositor brings Caulis and Kotlin: they are compileOnly, not in the jar.
plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://aristarkhkenebas.github.io/caulis/maven")
}

dependencies {
    compileOnly("io.github.aristarkhkenebas:caulis-wm:0.1.9")
}
