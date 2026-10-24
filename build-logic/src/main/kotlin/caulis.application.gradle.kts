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
