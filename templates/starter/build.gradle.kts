import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin {
    jvmToolchain(27)
    // Kotlin 2.4 writes Java 26 bytecode at most; it runs fine on Java 27.
    compilerOptions { jvmTarget = JvmTarget.JVM_26 }
}

tasks.withType<JavaCompile>().configureEach { options.release = 26 }

dependencies {
    implementation("dev.caulis:caulis-wm:0.1.0-SNAPSHOT")
    // The Bibata cursor (GPL-3.0, unlike Caulis itself). Remove this line for the system's
    // cursor theme, or if your compositor must not contain GPL files.
    implementation("dev.caulis:caulis-cursor-bibata:0.1.0-SNAPSHOT")
}

application {
    mainClass = "MainKt"
    applicationDefaultJvmArgs = listOf(
        // Caulis talks to C libraries (graphics, input) and needs this permission.
        "--enable-native-access=ALL-UNNAMED",
        // A garbage collector with very short pauses: smooth frames.
        "-XX:+UseZGC",
    )
}
