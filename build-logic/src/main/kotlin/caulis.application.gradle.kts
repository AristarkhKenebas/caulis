// A runnable program: everything from caulis.library, plus `./gradlew run`.

plugins {
    id("caulis.library")
    application
}

application {
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        // A small heap for a program that runs all day beside others, with short pauses: G1 at
        // ~48 MB, 256 MB at most (Java's default is a quarter of the computer's memory), the young
        // part small, two collector threads and one marking thread, and the heap growing only
        // once collecting takes a third of the time. ZGC, Java's lowest-pause collector, kept 78
        // MB more for an idle compositor (300 against 222 MB); -XX:+UseZGC when you prefer it.
        "-XX:+UseG1GC", "-Xms16m", "-Xmx256m", "-XX:SoftMaxHeapSize=48m", "-XX:MaxNewSize=16m",
        "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1", "-XX:MarkStackSize=256k", "-XX:GCTimeRatio=2",
        "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20", "-XX:G1PeriodicGCInterval=30000",
        // Protocol Buffers (the IPC socket) and the Kotlin compiler (hot reload) still use
        // sun.misc.Unsafe; this keeps Java from warning about it.
        "--sun-misc-unsafe-memory-access=allow",
    )
}

// Fast hot reload while developing with ./gradlew run (not in what installDist builds).
val development = configurations.create("development")
dependencies {
    development(project(":caulis-reload"))
}
tasks.named<JavaExec>("run") {
    classpath += development
    // (The Kotlin compiler runs inside it while developing: a heap for that.)
    maxHeapSize = "1g"
}
