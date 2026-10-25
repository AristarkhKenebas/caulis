// A runnable program: everything from caulis.library, plus `./gradlew run`.

plugins {
    id("caulis.library")
    application
}

application {
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        // Low-pause garbage collector, so frames are not delayed by GC.
        "-XX:+UseZGC",
    )
}

// Fast hot reload while developing with ./gradlew run (not in what installDist builds).
val development by configurations.creating
dependencies {
    development(project(":caulis-reload"))
}
tasks.named<JavaExec>("run") {
    classpath += development
    // The Kotlin compiler inside uses sun.misc.Unsafe; this keeps Java from warning about it.
    // (Added beside applicationDefaultJvmArgs: jvmArgs(...) would replace those.)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("--sun-misc-unsafe-memory-access=allow") }
}
