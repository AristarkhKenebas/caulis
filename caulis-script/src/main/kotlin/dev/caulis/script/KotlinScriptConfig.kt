package dev.caulis.script

import dev.caulis.log.Logger
import dev.caulis.wm.Caulis
import dev.caulis.wm.config.ConfigLanguage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.loadScriptFromJar
import kotlin.script.experimental.jvmhost.saveToJar
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * What a config.kts is: Kotlin, run as if inside `caulis { }` — everything Main.kt can say:
 *
 * ```kotlin
 * layout = MasterStack(Gaps(inner = 8))
 * bind("Super+B") { launch("firefox") }
 * rule { appId("mpv"); floating = true }
 * onWindowOpened { window -> if (window.appId == "music") run("move-to-workspace 2", window) }
 * ```
 */
@KotlinScript(fileExtension = "config.kts", compilationConfiguration = CaulisConfigScriptCompilation::class)
abstract class CaulisConfigScript

/** How config.kts is compiled: inside `caulis { }`, with Caulis's packages imported. */
object CaulisConfigScriptCompilation : ScriptCompilationConfiguration({
    implicitReceivers(Caulis::class)
    defaultImports("dev.caulis.wm.*", "dev.caulis.core.*", "dev.caulis.core.input.*", "dev.caulis.core.appearance.*", "dev.caulis.tiling.*", "dev.caulis.render.*", "java.time.LocalTime")
    jvm {
        // Everything the compositor has: Caulis, and whatever else it was built with.
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }
}) {
    private fun readResolve(): Any = CaulisConfigScriptCompilation
}

/**
 * config.kts: the configuration as a Kotlin script. It is compiled by a Java of its own (a
 * short-lived process: the Kotlin compiler, hundreds of megabytes once loaded, never stays in
 * the compositor), the first time and after each change — a few seconds; the compiled script
 * is kept in `~/.cache/<configName>/config-kts` (the newest [Caulis.compiledScriptsKept]
 * versions), so starting again only loads it: no compiler at all.
 *
 * A [ConfigLanguage]: Caulis finds it by itself when caulis-script is on the class path —
 * there is nothing to call. The compiler process's Java options and time limit: [compilerJvmOptions],
 * [compilerTimeoutSeconds].
 */
class KotlinScriptConfig : ConfigLanguage {

    private val log = Logger("script")

    override val fileName = "config.kts"

    override fun apply(caulis: Caulis, file: Path): List<String> {
        val name = file.fileName.toString()
        val code = try {
            Files.readString(file)
        } catch (e: Exception) {
            return listOf("$name cannot be read: ${e.message ?: e}")
        }
        val cache = cacheFolder(caulis)
        val jar = cache.resolve(key(code, name) + ".jar")
        val started = System.nanoTime()
        var script = load(jar)
        if (script == null) {
            val problems = try {
                Files.createDirectories(cache)
                compileElsewhere(code, name, jar)
            } catch (e: Exception) {
                log.info { "$name could not be compiled: $e" }
                return listOf("$name cannot be compiled: ${e.message ?: e}")
            }
            if (problems.isNotEmpty()) return problems
            keepNewest(cache, jar, caulis.compiledScriptsKept)
            script = load(jar) ?: return listOf("$name was compiled but cannot be loaded")
            log.info { "$name compiled in ${(System.nanoTime() - started) / 1_000_000} ms (a Java of its own)" }
        } else {
            log.info { "$name compiled before: taken from $cache" }
        }
        val result = runSuspending { BasicJvmScriptEvaluator()(script, ScriptEvaluationConfiguration { implicitReceivers(caulis) }) }
        log.info { "$name taken in ${(System.nanoTime() - started) / 1_000_000} ms" }
        val problems = problemsOf(name, result).toMutableList()
        // It compiled, but failed while running (an exception in it).
        val value = result.valueOrNull()?.returnValue
        if (value is ResultValue.Error) {
            val error = value.error
            val line = error.stackTrace.firstOrNull { it.fileName == name }?.lineNumber
            problems += "$name${if (line != null && line > 0) ":$line" else ""}: ${error.message ?: error}"
        }
        return problems.distinct()
    }

    // The script compiled before into [jar] (null: not there, or not usable — compiled anew).
    private fun load(jar: Path): kotlin.script.experimental.api.CompiledScript? {
        if (!Files.isRegularFile(jar)) return null
        return try {
            jar.toFile().loadScriptFromJar(checkMissingDependencies = true)?.also {
                // (Newest again, so it is not the one cleared away.)
                jar.toFile().setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            log.info { "$jar cannot be loaded ($e): compiled anew" }
            null
        }
    }

    // The compiler in a Java of its own: [code] into [jar]; what is wrong in it.
    private fun compileElsewhere(code: String, name: String, jar: Path): List<String> {
        val folder = jar.parent
        val source = Files.createTempFile(folder, "compiling-", ".kts")
        val output = Files.createTempFile(folder, "compiling-", ".out")
        try {
            Files.writeString(source, code)
            val java = ProcessHandle.current().info().command().orElse(null)?.takeIf { Files.isExecutable(Path.of(it)) }
                ?: Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classpath = System.getProperty("java.class.path").orEmpty()
            val options = compilerJvmOptions
            val timeout = compilerTimeoutSeconds
            val command = listOf(java) + options + listOf("--enable-native-access=ALL-UNNAMED", "-cp", classpath, ScriptCompilerProcess::class.java.name, source.toString(), jar.toString(), name)
            log.info { "Compiling $name in a Java of its own (${options.joinToString(" ")}, at most $timeout s)" }
            val process = dev.caulis.ffi.Processes.start(
                ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))),
            )
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return listOf("$name: compiling took longer than $timeout seconds and was stopped (KotlinScriptConfig.compilerTimeoutSeconds)")
            }
            val lines = Files.readAllLines(output)
            val problems = lines.filter { it.startsWith(PROBLEM) }.map { it.removePrefix(PROBLEM).replace("\\n", "\n").replace("\\\\", "\\") }
            if (lines.none { it == DONE } && problems.isEmpty()) return listOf("$name cannot be compiled: the compiler ended with code ${process.exitValue()}")
            if (problems.isEmpty() && !Files.isRegularFile(jar)) return listOf("$name cannot be compiled (the compiler said nothing)")
            return problems
        } finally {
            runCatching { Files.deleteIfExists(source) }
            runCatching { Files.deleteIfExists(output) }
        }
    }

    // Only the newest [kept] compiled scripts stay (each change of the script, or of Caulis,
    // makes another; Caulis.compiledScriptsKept); [using] is the one about to be used, kept too.
    private fun keepNewest(cache: Path, using: Path, kept: Int) {
        try {
            val jars = Files.list(cache).use { all -> all.filter { it.fileName.toString().endsWith(".jar") && it != using }.toList() }
                .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            for (old in jars.drop(kept - 1)) {
                Files.deleteIfExists(old)
                log.trace { "Old compiled script taken away: ${old.fileName}" }
            }
        } catch (e: Exception) {
            log.warn("The compiled scripts in $cache could not be tidied: $e")
        }
    }

    private fun cacheFolder(caulis: Caulis): Path {
        // (Somewhere else for tests.)
        System.getProperty("caulis.scriptCache")?.let { return Path.of(it) }
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".cache")
        return base.resolve(caulis.configName).resolve("config-kts")
    }

    // The script (and its name: the compiled class is named by it), and what it was compiled
    // against: another Caulis needs another compilation.
    private fun key(code: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(name.toByteArray())
        digest.update(0)
        digest.update(code.toByteArray())
        digest.update(System.getProperty("java.class.path").orEmpty().toByteArray())
        for (entry in System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)) {
            val jar = File(entry)
            if (jar.isFile) digest.update("${jar.length()}:${jar.lastModified()}".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        /** The compiler process's Java options by default: small and quick to start (it runs a few seconds and ends). */
        @JvmField
        val DEFAULT_COMPILER_JVM_OPTIONS: List<String> = listOf("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss4m", "-Djava.awt.headless=true")

        /** The compiler process's Java options (set them before the configuration is read, in your Main). */
        @JvmStatic
        @Volatile
        var compilerJvmOptions: List<String> = DEFAULT_COMPILER_JVM_OPTIONS

        /** At most this long (seconds) for one compilation (180): then it is stopped, and that is said as a problem of the script. */
        @JvmStatic
        @Volatile
        var compilerTimeoutSeconds: Long = 180
            set(value) {
                require(value > 0) { "compilerTimeoutSeconds must be more than 0, not $value" }
                field = value
            }

        internal const val PROBLEM = "problem "
        internal const val DONE = "done"

        internal fun problemsOf(name: String, result: ResultWithDiagnostics<*>): List<String> = result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .map { report ->
                val line = report.location?.start?.line
                "$name${if (line != null) ":$line" else ""}: ${report.message}"
            }
    }
}

/**
 * The compiler's side of [KotlinScriptConfig] (a Java of its own): compiles a config.kts into a
 * jar, says each problem on a line, and ends. Arguments: the script, the jar, its name.
 */
internal object ScriptCompilerProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val (source, jar, name) = args
        val code = Files.readString(Path.of(source))
        val out = StringBuilder()
        val result = try {
            runSuspending {
                JvmScriptCompiler(defaultJvmScriptingHostConfiguration)(code.toScriptSource(name), createJvmCompilationConfigurationFromTemplate<CaulisConfigScript>())
            }
        } catch (e: Exception) {
            print("${KotlinScriptConfig.PROBLEM}$name cannot be compiled: ${(e.message ?: e.toString()).replace("\n", " ")}\n${KotlinScriptConfig.DONE}\n")
            System.out.flush()
            Runtime.getRuntime().halt(0)
            return
        }
        for (problem in KotlinScriptConfig.problemsOf(name, result)) out.append(KotlinScriptConfig.PROBLEM).append(problem.replace("\\", "\\\\").replace("\n", "\\n")).append('\n')
        val compiled = result.valueOrNull() as? KJvmCompiledScript
        if (compiled != null) {
            // (Written whole, then put in its place: the compositor never loads half a jar.)
            val target = Path.of(jar)
            val partial = Files.createTempFile(target.parent, "compiled-", ".part")
            try {
                compiled.saveToJar(partial.toFile())
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
        out.append(KotlinScriptConfig.DONE).append('\n')
        print(out)
        System.out.flush()
        // (The compiler may leave threads of its own behind.)
        Runtime.getRuntime().halt(0)
    }
}

// Runs [block] (a suspend function of the scripting API) to its end on this thread, waiting
// for it: as kotlinx.coroutines' runBlocking, without needing it.
internal fun <T> runSuspending(block: suspend () -> T): T {
    val done = java.util.concurrent.CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        outcome = result
        done.countDown()
    })
    done.await()
    return outcome!!.getOrThrow()
}
