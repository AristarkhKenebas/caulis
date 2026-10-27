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
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

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
 * config.kts: the configuration as a Kotlin script, compiled by the compositor itself. The
 * first time (and after each change) that takes a few seconds; the compiled script is kept
 * in `~/.cache/<configName>/config-kts` (the newest [Caulis.compiledScriptsKept] versions), so starting again is quick.
 *
 * A [ConfigLanguage]: Caulis finds it by itself when caulis-script is on the class path —
 * there is nothing to call.
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
        val host = BasicJvmScriptingHost(
            ScriptingHostConfiguration {
                jvm {
                    compilationCache(
                        CompiledScriptJarsCache { source, configuration ->
                            Files.createDirectories(cache)
                            cache.resolve(key(source, configuration) + ".jar").toFile().also { keepNewest(cache, it.toPath(), caulis.compiledScriptsKept) }
                        },
                    )
                }
            },
        )
        val started = System.nanoTime()
        val result = host.eval(
            code.toScriptSource(name),
            createJvmCompilationConfigurationFromTemplate<CaulisConfigScript>(),
            ScriptEvaluationConfiguration { implicitReceivers(caulis) },
        )
        log.info { "$name taken in ${(System.nanoTime() - started) / 1_000_000} ms" }
        val problems = result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .map { report ->
                val line = report.location?.start?.line
                "$name${if (line != null) ":$line" else ""}: ${report.message}"
            }
            .toMutableList()
        // It compiled, but failed while running (an exception in it).
        val value = result.valueOrNull()?.returnValue
        if (value is ResultValue.Error) {
            val error = value.error
            val line = error.stackTrace.firstOrNull { it.fileName == name }?.lineNumber
            problems += "$name${if (line != null && line > 0) ":$line" else ""}: ${error.message ?: error}"
        }
        return problems.distinct()
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

    // The script, and what it was compiled against (another Caulis needs another compilation).
    @Suppress("UNUSED_PARAMETER")
    private fun key(source: SourceCode, configuration: ScriptCompilationConfiguration): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.text.toByteArray())
        digest.update(System.getProperty("java.class.path").orEmpty().toByteArray())
        for (entry in System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)) {
            val jar = File(entry)
            if (jar.isFile) digest.update("${jar.length()}:${jar.lastModified()}".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}
