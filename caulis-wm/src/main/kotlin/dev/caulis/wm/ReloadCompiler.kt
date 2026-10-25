package dev.caulis.wm

import java.nio.file.Path

/**
 * Compiles a project's sources inside the running compositor, for [Caulis.hotReload] —
 * in well under the time a Gradle build takes. The module caulis-reload provides one
 * (meant for development only: add it to the `run` task, not to what you install);
 * without it, the project's own Gradle compiles.
 *
 * Found with [java.util.ServiceLoader].
 */
interface ReloadCompiler {

    /**
     * Why the project in [root] needs its Gradle build instead (compiler plugins, code
     * generation, ...), or null if this compiler can compile it.
     */
    fun cannotCompile(root: Path): String?

    /**
     * Compiles the sources of the project in [root] (Kotlin and Java under `src/main`)
     * against [classpath] into [output] (an empty folder).
     */
    fun compile(root: Path, classpath: List<Path>, output: Path): Result

    /**
     * Compiles [rounds] times without using the result, so the first real change is quick
     * too (a compiler in a JVM gets faster over several runs); stops early when [stop] says so.
     */
    fun warmUp(root: Path, classpath: List<Path>, rounds: Int, stop: () -> Boolean) {
        for (round in 1..rounds) {
            val folder = java.nio.file.Files.createTempDirectory("caulis-warm-up")
            try {
                if (!compile(root, classpath, folder).compiled) return
            } finally {
                folder.toFile().deleteRecursively()
            }
            if (stop()) return
        }
    }

    /** The compositor stops. */
    fun close() {}

    /** Whether it compiled, and the problems as "src/main/kotlin/Main.kt:15: Unresolved reference 'x'". */
    class Result(val compiled: Boolean, val problems: List<String>)
}
