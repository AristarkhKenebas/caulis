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
    implementation("io.github.aristarkhkenebas:caulis-wm:0.1.7")
    // Settings in Lua (config.lua) or as a Kotlin script (config.kts) in ~/.config/<name>, for
    // people who use your compositor without building it — add what you want them to have.
    // (caulis-script is big: it contains the Kotlin compiler.)
    // implementation("io.github.aristarkhkenebas:caulis-lua:0.1.7")
    // implementation("io.github.aristarkhkenebas:caulis-script:0.1.7")
    // Your own bar, dock, launcher in Compose: compose { } in your configuration (needs google()
    // in the repositories above).
    // implementation("io.github.aristarkhkenebas:caulis-compose:0.1.7")
    // Screenshots (Print): add it and call screenshots() in your configuration.
    // implementation("io.github.aristarkhkenebas:caulis-screenshot:0.1.7")
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
val development = configurations.create("development")
dependencies {
    development("io.github.aristarkhkenebas:caulis-reload:0.1.7")
}
tasks.named<JavaExec>("run") {
    classpath += development
}
