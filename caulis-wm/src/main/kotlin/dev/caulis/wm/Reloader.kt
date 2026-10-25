package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.log.Logger
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Reloads the configuration while the compositor runs ([Caulis.hotReload]): when a file in
 * the project's `src` changes, the project is compiled again (its own Gradle wrapper), its
 * classes are loaded fresh and its `main` runs once more — where `caulis { }` (or `run()`)
 * now hands its configuration over instead of starting a second compositor. The running
 * one takes it on ([Running.reconfigure]); windows and programs stay.
 *
 * If it does not compile or the new code fails, the old configuration keeps running and a
 * notice says why.
 */
internal class Reloader private constructor(
    private val running: Running,
    private val project: Project,
    /** Compiles in this JVM (caulis-reload), or null: the project's Gradle compiles. */
    private val compiler: ReloadCompiler?,
) : AutoCloseable {

    /** Where the user's project is, as found from how this program was started. */
    class Project(val root: Path, val sources: Path, val outputs: List<Path>, val gradlew: Path?, val mainClass: String, val arguments: List<String>)

    private val log = Logger("reload")
    private val watcher: WatchService = FileSystems.getDefault().newWatchService()
    private val thread = Thread.ofPlatform().daemon().name("caulis-reload").unstarted(::watch)

    @Volatile
    private var closed = false

    @Volatile
    private var build: Process? = null

    private fun watch() {
        try {
            registerAll(project.sources)
            // Compilers are slow the first times (their code warms up): one quiet compile once
            // the compositor has settled, so the first real change is taken on quickly too.
            // Changes meanwhile wait in the watcher.
            Thread.sleep(WARM_UP_DELAY_MILLIS)
            if (!closed) warmUp()
            while (!closed) {
                val key = pendingKey?.also { pendingKey = null } ?: watcher.take()
                var relevant = key.pollEvents().any { event -> (event.context() as? Path)?.let(::relevant) ?: true }
                // New folders are watched too (a new package).
                (key.watchable() as? Path)?.let { folder ->
                    runCatching { Files.list(folder).use { entries -> entries.filter { it.isDirectory() }.forEach(::registerAll) } }
                }
                key.reset()
                // Editors save in several steps: wait until it is quiet for a moment.
                while (true) {
                    val more = watcher.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS) ?: break
                    if (more.pollEvents().any { event -> (event.context() as? Path)?.let(::relevant) ?: true }) relevant = true
                    more.reset()
                }
                if (relevant && !closed) reload()
            }
        } catch (_: ClosedWatchServiceException) {
            // closed: the compositor stops
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            log.warn("Watching ${project.sources} for changes stopped: $e (restart to reload again)")
        }
    }

    // Editors' backup and lock files are no reason to compile.
    private fun relevant(file: Path): Boolean {
        val name = file.name
        return !(name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".swx") || name.startsWith(".#") || name.startsWith("#"))
    }

    private fun registerAll(folder: Path) {
        Files.walk(folder).use { all ->
            all.filter { it.isDirectory() }.forEach { it.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
        }
    }

    private fun warmUp() {
        val started = System.nanoTime()
        if (compiler != null) {
            compiler.warmUp(project.root, compileClasspath(), WARM_UP_ROUNDS) { closed || changesWaiting() }
            log.trace { "Warm-up done in ${(System.nanoTime() - started) / 1_000_000} ms" }
        } else {
            // (The configuration's own compile task once more, so the Kotlin compiler warms up too.)
            compile("compileKotlin", "--rerun")?.let { (status, _) -> log.trace { "Warm-up build done in ${(System.nanoTime() - started) / 1_000_000} ms (status $status)" } }
        }
    }

    // True if a change is waiting (it is not taken from the watcher: the loop sees it next).
    private fun changesWaiting(): Boolean = pendingKey != null || watcher.poll()?.also { pendingKey = it } != null

    // A change seen while warming up, for the loop to handle first.
    private var pendingKey: java.nio.file.WatchKey? = null

    // Each reload compiles into a folder of its own (the one before may still be in use).
    private var reloads = 0

    // The classpath to compile against: what the compositor runs with, without the project's
    // own compiled classes (its sources are compiled instead).
    private fun compileClasspath(): List<Path> =
        System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotEmpty() }
            .map { Path.of(it).toAbsolutePath() }.filter { entry -> !entry.startsWith(project.root.resolve("build")) }

    private fun fresh(folder: Path) {
        folder.toFile().deleteRecursively()
        Files.createDirectories(folder)
    }

    private fun reload() {
        log.info { "A file in ${project.sources} changed: compiling" }
        val started = System.nanoTime()
        // Where the new classes are: a fresh folder (compiled here), or the build's own (Gradle).
        val classes: List<Path>
        if (compiler != null) {
            val folder = project.root.resolve("build/caulis-reload/${++reloads}")
            fresh(folder)
            // The folder from two reloads ago is no longer used by anything.
            project.root.resolve("build/caulis-reload/${reloads - 2}").toFile().deleteRecursively()
            val result = compiler.compile(project.root, compileClasspath(), folder)
            if (!result.compiled) {
                doesNotCompile(result.problems.take(MAX_LINES))
                return
            }
            classes = listOf(folder, project.root.resolve("src/main/resources")) + project.outputs.filter { "resources" in it.toString() }
        } else {
            val output = compile() ?: return
            if (output.first != 0) {
                doesNotCompile(compileProblems(output.second))
                return
            }
            classes = project.outputs
        }
        val compiled = System.nanoTime()
        val next = try {
            evaluate(classes)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            // The user's own mistake, at the line of their code where it happened.
            val real = ((e as? InvocationTargetException)?.cause ?: e).let { (it as? ExceptionInInitializerError)?.cause ?: it }
            val where = real.stackTrace.firstOrNull { it.className.substringBefore('$') in loadedHere }?.let { "${it.fileName}:${it.lineNumber}: " } ?: ""
            val cause = (real as? ReloadFailed)?.message ?: "$where${real.message ?: real}"
            log.warn("The new configuration failed; the one before keeps running: $cause")
            notify(cause, "Your configuration failed — the one before keeps running")
            return
        }
        log.trace { "Compiled in ${(compiled - started) / 1_000_000} ms, loaded and run in ${(System.nanoTime() - compiled) / 1_000_000} ms" }
        running.loop.post { takeOn(next, started) }
    }

    private fun doesNotCompile(problems: List<String>) {
        log.warn("The configuration does not compile; the one before keeps running:\n" + problems.joinToString("\n"))
        notify(problems.joinToString("\n"), "Your configuration does not compile — the one before keeps running")
    }

    // Runs the project's Gradle to compile it: exit status and what it printed (null when closed or Gradle is missing).
    private fun compile(vararg tasks: String = arrayOf("classes")): Pair<Int, List<String>>? {
        val gradlew = project.gradlew ?: return null
        val process = try {
            ProcessBuilder(listOf(gradlew.toString(), "--quiet", "--console=plain", "-p", project.root.toString()) + tasks)
                .directory(gradlew.parent.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            log.warn("Gradle could not be started to compile the configuration: $e")
            notify("$e", "Your configuration could not be compiled")
            return null
        }
        build = process
        process.outputStream.close()
        val lines = process.inputStream.bufferedReader().readLines()
        val status = process.waitFor()
        build = null
        if (closed) return null
        return status to lines
    }

    // The compiler's own words, shortest first: "Main.kt:12: Unresolved reference 'lyaout'".
    private fun compileProblems(lines: List<String>): List<String> {
        val errors = lines.filter { it.startsWith("e: ") }.map { line ->
            val text = line.removePrefix("e: ").removePrefix("file://")
            val match = Regex("""^(.*?):(\d+):(\d+) (.*)$""").find(text)
            if (match == null) text else {
                val (file, row, _, message) = match.destructured
                "${runCatching { project.root.relativize(Path.of(file)) }.getOrDefault(Path.of(file).fileName)}:$row: $message"
            }
        }
        return errors.ifEmpty { lines.filter { it.isNotBlank() && !it.startsWith("*") }.take(4) }.take(MAX_LINES)
    }

    // Names of the classes the last fresh loader found in the project (to point at the user's line).
    private var loadedHere: Set<String> = emptySet()

    // Loads the project's classes fresh and runs its main, which hands over its configuration.
    private fun evaluate(classes: List<Path>): Caulis {
        val loader = ProjectLoader(classes, Caulis::class.java.classLoader)
        val slot = arrayOfNulls<Caulis>(1)
        capturing.set(slot)
        try {
            val main = Class.forName(project.mainClass, true, loader)
            main.getMethod("main", Array<String>::class.java).invoke(null, project.arguments.toTypedArray())
        } catch (e: InvocationTargetException) {
            if (e.cause !is Captured) {
                loadedHere = loader.found
                throw e
            }
        } finally {
            capturing.remove()
            loadedHere = loader.found
        }
        return slot[0] ?: throw ReloadFailed("main() did not start Caulis (caulis { } or run()), so there was nothing to take on")
    }

    // On the compositor's thread: the new configuration replaces the old one (back to it if that fails).
    private fun takeOn(next: Caulis, started: Long) {
        val before = running.config
        val (restartOnly, notes) = try {
            running.reconfigure(next)
        } catch (e: Exception) {
            log.warn("Taking on the new configuration failed, back to the one before: $e")
            runCatching { running.reconfigure(before) }
            notify("$e", "Your configuration failed — the one before keeps running")
            return
        }
        problems.forEach(running.desktop::dismiss)
        problems.clear()
        val millis = (System.nanoTime() - started) / 1_000_000
        log.info { "Configuration reloaded in $millis ms" }
        val text = buildList {
            if (restartOnly.isNotEmpty()) add("After a restart: ${restartOnly.joinToString()}.")
            notes.forEach { add(it.replaceFirstChar(Char::uppercase) + ".") }
        }
        running.desktop.notify(text.joinToString("\n").ifEmpty { "Windows and programs stayed as they were." }, NoticeLevel.INFO, "Configuration reloaded")
    }

    // Our own error notices: once a reload works, they are out of date and go.
    private val problems = ArrayList<dev.caulis.core.Notice>()

    private fun notify(text: String, title: String) {
        running.loop.post { problems += running.desktop.notify(text, NoticeLevel.ERROR, title) }
    }

    override fun close() {
        closed = true
        runCatching { compiler?.close() }
        build?.destroy()
        watcher.close()
        thread.interrupt()
    }

    /** Loads the project's own classes fresh (the rest — Caulis, Kotlin, ... — from [parent], shared). */
    private class ProjectLoader(folders: List<Path>, parent: ClassLoader) : URLClassLoader(folders.map { it.toUri().toURL() }.toTypedArray(), parent) {
        val found = HashSet<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name) ?: try {
                findClass(name).also { found += name }
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }

        override fun getResource(name: String) = findResource(name) ?: super.getResource(name)
    }

    /** Thrown by [Caulis.run] while a reload runs main: the configuration is in the slot. */
    internal class Captured : Error(null, null, false, false)

    private class ReloadFailed(message: String) : Exception(message)

    companion object {
        private val log = Logger("reload")
        private const val QUIET_MILLIS = 150L
        private const val WARM_UP_DELAY_MILLIS = 10_000L
        private const val WARM_UP_ROUNDS = 3
        private const val MAX_LINES = 6

        // Set while a reload runs main: caulis { } and run() put their configuration here and stop.
        internal val capturing = ThreadLocal<Array<Caulis?>?>()

        /** Starts watching for [running]'s configuration, if the program was started from a project with sources. */
        fun start(running: Running): Reloader? {
            val project = findProject() ?: return null
            // In this JVM if caulis-reload is there and the project needs nothing more; else Gradle.
            val found = runCatching { java.util.ServiceLoader.load(ReloadCompiler::class.java, Caulis::class.java.classLoader).firstOrNull() }
                .onFailure { log.warn("The hot reload compiler could not be loaded: $it") }.getOrNull()
            val cannot = found?.cannotCompile(project.root)
            val compiler = found.takeIf { cannot == null }
            if (compiler == null && project.gradlew == null) return null.also { why("no Gradle wrapper (gradlew) in ${project.root} or above, and no caulis-reload") }
            log.info {
                "Hot reload: watching ${project.sources}, " + when {
                    compiler != null -> "compiled right here (caulis-reload)"
                    cannot != null -> "compiled with ${project.gradlew}: $cannot"
                    else -> "compiled with ${project.gradlew} (add caulis-reload to the run task for much faster reloads)"
                }
            }
            return Reloader(running, project, compiler).also { it.thread.start() }
        }

        // The project this program was started from: its main class among compiled folders
        // under some <project>/build, with <project>/src (and a Gradle wrapper above, if any).
        private fun findProject(): Project? {
            val command = System.getProperty("sun.java.command")?.trim()?.split(Regex("\\s+")) ?: return null.also { why("how it was started is unknown") }
            val mainClass = command.first()
            if (mainClass.endsWith(".jar")) return null.also { why("it runs from a jar (an installed program), not from its project") }
            val folders = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparatorChar)
                .filter { it.isNotEmpty() }.map { Path.of(it).toAbsolutePath() }.filter { it.isDirectory() }
            val output = folders.firstOrNull { Files.exists(it.resolve(mainClass.replace('.', '/') + ".class")) }
                ?: return null.also { why("the class $mainClass is not in a compiled folder (an installed program?)") }
            val build = generateSequence(output) { it.parent }.firstOrNull { it.name == "build" }
                ?: return null.also { why("$output is not inside a project's build folder") }
            val root = build.parent
            val sources = root.resolve("src")
            if (!sources.isDirectory()) return null.also { why("$root has no src folder") }
            val gradlew = generateSequence(root) { it.parent }.map { it.resolve("gradlew") }.firstOrNull { Files.isExecutable(it) }
            return Project(root, sources, folders.filter { it.startsWith(build) }, gradlew, mainClass, command.drop(1))
        }

        private fun why(reason: String) = log.info { "Hot reload is off: $reason" }
    }
}
