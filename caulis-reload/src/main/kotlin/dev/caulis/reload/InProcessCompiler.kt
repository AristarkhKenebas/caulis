package dev.caulis.reload

import dev.caulis.log.Logger
import dev.caulis.wm.ReloadCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Compiles a project's sources in this JVM: Kotlin with the Kotlin compiler, then Java
 * with the JDK's own (javax.tools), against the classes the compositor runs with. Both
 * stay loaded and warm, so a change takes well under a second instead of a Gradle build.
 *
 * Projects that need more than plain compiling — compiler plugins (serialization,
 * Compose, all-open, ...), annotation processors, KSP/kapt, own source sets or compiler
 * flags — are left to Gradle ([cannotCompile] says why).
 */
class InProcessCompiler : ReloadCompiler {

    private val log = Logger("reload")

    override fun cannotCompile(root: Path): String? {
        val build = listOf("build.gradle.kts", "build.gradle").map(root::resolve).firstOrNull { it.isRegularFile() }
            ?: return "there is no build.gradle.kts in $root"
        val text = build.readText()
        val needs = GRADLE_ONLY.firstOrNull { it.first.containsMatchIn(text) } ?: return null
        return "${build.fileName} uses ${needs.second}, which only its Gradle build knows how to do"
    }

    override fun compile(root: Path, classpath: List<Path>, output: Path): ReloadCompiler.Result {
        val main = root.resolve("src/main")
        val sources = listOf("kotlin", "java").map(main::resolve).filter { it.isDirectory() }.flatMap { folder ->
            Files.walk(folder).use { all -> all.filter { it.isRegularFile() && it.extension in setOf("kt", "java") }.toList() }
        }
        val kotlin = sources.filter { it.extension == "kt" }
        val java = sources.filter { it.extension == "java" }
        if (sources.isEmpty()) return ReloadCompiler.Result(false, listOf("no Kotlin or Java files in $main"))
        // The compiler's own jars are there only for compiling: nothing to look through.
        val against = classpath.filter { entry -> TOOLING.none { entry.fileName.toString().startsWith(it) } }
        val problems = ArrayList<String>()
        fun where(path: String?, line: Int): String {
            if (path == null) return ""
            val shown = runCatching { root.relativize(Path.of(path)) }.getOrDefault(Path.of(path))
            return if (line > 0) "$shown:$line: " else "$shown: "
        }

        // Kotlin first (it reads the Java files for their declarations), then Java against it.
        if (kotlin.isNotEmpty()) {
            val started = System.nanoTime()
            val collector = object : MessageCollector {
                var errors = false
                override fun clear() {}
                override fun hasErrors() = errors
                override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                    when {
                        severity.isError -> {
                            errors = true
                            problems += where(location?.path, location?.line ?: 0) + message
                        }
                        severity == CompilerMessageSeverity.WARNING || severity == CompilerMessageSeverity.STRONG_WARNING ->
                            log.trace { "Kotlin: ${where(location?.path, location?.line ?: 0)}$message" }
                        else -> {}
                    }
                }
            }
            val compiler = K2JVMCompiler()
            val arguments = compiler.createArguments()
            compiler.parseArguments(
                arrayOf(
                    "-d", output.toString(),
                    "-classpath", against.joinToString(File.pathSeparator),
                    "-jvm-target", jvmTarget(),
                    "-module-name", root.fileName.toString(),
                    // The standard library is on the classpath already (the compositor runs with it).
                    "-no-stdlib", "-no-reflect",
                ) + (kotlin + java).map(Path::toString),
                arguments,
            )
            val exit = try {
                compiler.exec(collector, Services.EMPTY, arguments)
            } catch (e: Exception) {
                log.warn("The Kotlin compiler failed: $e")
                return ReloadCompiler.Result(false, listOf("the Kotlin compiler failed: $e"))
            }
            log.trace { "Kotlin: ${kotlin.size} files in ${(System.nanoTime() - started) / 1_000_000} ms ($exit)" }
            if (exit != ExitCode.OK || collector.errors) return ReloadCompiler.Result(false, problems.ifEmpty { listOf("the Kotlin compiler said $exit") })
        }

        if (java.isNotEmpty()) {
            val started = System.nanoTime()
            val javac = ToolProvider.getSystemJavaCompiler()
                ?: return ReloadCompiler.Result(false, listOf("this Java has no compiler (a JRE, not a JDK): Java files cannot be compiled"))
            val diagnostics = DiagnosticCollector<JavaFileObject>()
            javac.getStandardFileManager(diagnostics, Locale.ENGLISH, Charsets.UTF_8).use { files ->
                val options = listOf(
                    "-d", output.toString(),
                    "-classpath", (listOf(output) + against).joinToString(File.pathSeparator),
                    "--release", Runtime.version().feature().toString(),
                    "-proc:none", "-encoding", "UTF-8",
                )
                val ok = javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(java)).call()
                for (d in diagnostics.diagnostics) {
                    if (d.kind == Diagnostic.Kind.ERROR) problems += where(d.source?.toUri()?.path, d.lineNumber.toInt()) + d.getMessage(Locale.ENGLISH)
                }
                log.trace { "Java: ${java.size} files in ${(System.nanoTime() - started) / 1_000_000} ms" }
                if (!ok) return ReloadCompiler.Result(false, problems.ifEmpty { listOf("the Java compiler failed") })
            }
        }
        return ReloadCompiler.Result(true, emptyList())
    }

    // The newest bytecode the Kotlin compiler writes that this JVM runs.
    private fun jvmTarget(): String {
        val runtime = Runtime.version().feature()
        return JvmTarget.entries.map { it.description }.mapNotNull { d -> d.removePrefix("1.").toIntOrNull()?.let { it to d } }
            .filter { it.first <= runtime }.maxByOrNull { it.first }?.second ?: JvmTarget.DEFAULT.description
    }

    private companion object {
        // Jars on the classpath only because of this module.
        val TOOLING = listOf("kotlin-compiler-embeddable", "kotlin-daemon-embeddable", "kotlin-build-tools-api", "kotlin-script-runtime", "caulis-reload")

        // What in a build file means plain compiling is not enough, and what it is called.
        val GRADLE_ONLY = listOf(
            Regex("""kotlin\(\s*"plugin\.""") to "a Kotlin compiler plugin",
            Regex("""org\.jetbrains\.kotlin\.plugin""") to "a Kotlin compiler plugin",
            Regex("""compose""", RegexOption.IGNORE_CASE) to "Compose",
            Regex("""\bkapt\b|\bksp\b|com\.google\.devtools\.ksp""") to "code generation (kapt/KSP)",
            Regex("""annotationProcessor\s*\(""") to "annotation processors",
            Regex("""freeCompilerArgs|optIn\s*\(|languageVersion|apiVersion""") to "own compiler settings",
            Regex("""srcDirs?\s*\(|setSrcDirs|srcDirs\s*=""") to "its own source folders",
        )
    }
}
