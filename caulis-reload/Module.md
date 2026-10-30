# Module caulis-reload

Fast hot reload, for development only: when you save a file, your configuration is compiled
right inside the running compositor (the Kotlin compiler, and the JDK's own for Java files)
instead of a Gradle build, incrementally (only what changed, and what depends on it) —
well under a second from saving to seeing it. Without this
module, hot reload uses the project's Gradle and takes several seconds.

Add it to the `run` task only (the starter project does), not to what you install: it
contains the Kotlin compiler (about 60 MB) and an installed compositor watches no sources.

```kotlin
val development = configurations.create("development")
dependencies { development("io.github.aristarkhkenebas:caulis-reload:0.1.8") }
tasks.named<JavaExec>("run") {
    classpath += development
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("--sun-misc-unsafe-memory-access=allow") }
}
```

Projects that need more than plain compiling — Kotlin compiler plugins, Compose, kapt/KSP,
annotation processors, their own compiler settings or source folders — are compiled by their
Gradle build as before; the log (area `reload`) says why.

# Package dev.caulis.reload

[dev.caulis.reload.InProcessCompiler]: the compiler, found by Caulis through `ServiceLoader`.
