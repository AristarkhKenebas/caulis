// A jar plugin. The compositor brings Caulis and Kotlin: they are compileOnly, not in the jar.
plugins {
    kotlin("jvm") version "2.4.21"
}

// (Kotlin cannot write JDK 27's bytecode yet: both at 26, which newer JDKs run.)
java { targetCompatibility = JavaVersion.VERSION_26 }

repositories {
    mavenCentral()
    maven("https://aristarkhkenebas.github.io/caulis/maven")
}

dependencies {
    compileOnly("io.github.aristarkhkenebas:caulis-wm:0.1.9")
}
