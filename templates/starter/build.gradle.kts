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
        // Protocol Buffers (the IPC socket) and the Kotlin compiler (hot reload) still use
        // sun.misc.Unsafe; this keeps Java from warning about it.
        "--sun-misc-unsafe-memory-access=allow",
    )
}

// Only while developing with ./gradlew run: change Main.kt while the compositor runs and it
// is compiled right inside it, taken on in about a second. Not in what installDist builds.
val development by configurations.creating
dependencies {
    development("dev.caulis:caulis-reload:0.1.0-SNAPSHOT")
}
tasks.named<JavaExec>("run") {
    classpath += development
}
