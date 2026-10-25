package dev.caulis.reload

import dev.caulis.log.Logger
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

/**
 * Kotlin's own incremental compilation (the Build Tools API, as the Gradle plugin uses it),
 * for one project: after the first time, only the files that changed are compiled again,
 * and the ones that depend on what changed in them. Its classes and caches live in
 * `build/caulis-reload/ic` and are updated in place.
 */
@OptIn(ExperimentalBuildToolsApi::class)
internal class IncrementalKotlin(private val root: Path, private val moduleName: String) : AutoCloseable {

    private val log = Logger("reload")
    private val home = root.resolve("build/caulis-reload/ic")

    /** Where the compiled classes are (complete, after a compile that worked). */
    val classes: Path = home.resolve("classes")
    private val caches = home.resolve("caches")
    private val snapshots = home.resolve("classpath")

    private val toolchains = KotlinToolchains.loadImplementation(IncrementalKotlin::class.java.classLoader)
    private val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
    private val session = toolchains.createBuildSession()

    // The sources as they were when they last compiled (time and size), or null: not known (yet).
    private var compiled: Map<Path, Pair<Long, Long>>? = null

    init {
        log.trace { "Incremental Kotlin compilation ${toolchains.getCompilerVersion()} in $home" }
    }

    /**
     * Compiles what changed in [sources] (Kotlin and Java files, all of them) against
     * [classpath]; problems go to [problems]. True if it compiled; null if incremental
     * compilation itself failed (then compile the whole way).
     */
    fun compile(sources: List<Path>, classpath: List<Path>, target: JvmTarget, problems: MutableList<String>, where: (String?, Int) -> String, force: Boolean = false): Boolean? {
        val now = sources.associateWith { it.getLastModifiedTime().toMillis() to it.fileSize() }
        val before = compiled
        val changes = if (before == null || force) SourcesChanges.Unknown else SourcesChanges.Known(
            modifiedFiles = now.filter { (file, stamp) -> before[file] != stamp }.keys.map(Path::toFile),
            removedFiles = before.keys.filter { it !in now }.map(Path::toFile),
        )
        if (changes is SourcesChanges.Known) log.trace { "Changed: ${changes.modifiedFiles.joinToString { it.name }}${if (changes.removedFiles.isEmpty()) "" else ", gone: " + changes.removedFiles.joinToString { it.name }}" }
        val renderer = object : CompilerMessageRenderer {
            override fun render(severity: CompilerMessageRenderer.Severity, message: String, location: CompilerMessageRenderer.SourceLocation?): String {
                val text = where(location?.path, location?.line ?: 0) + message
                when (severity) {
                    CompilerMessageRenderer.Severity.ERROR -> problems += text
                    CompilerMessageRenderer.Severity.WARNING -> log.trace { "Kotlin: $text" }
                    else -> {}
                }
                return text
            }
        }
        return try {
            Files.createDirectories(classes)
            val builder = jvm.jvmCompilationOperationBuilder(sources, classes)
            builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = classpath
            builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = moduleName
            builder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = target
            // The standard library is on the classpath already (the compositor runs with it).
            builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
            builder.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
            builder[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = renderer
            val ic = builder.snapshotBasedIcConfigurationBuilder(caches, changes, classpath.map(::snapshotOf))
            ic[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = root
            ic[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = root.resolve("build")
            // A compile that fails leaves the classes as they were.
            ic[JvmSnapshotBasedIncrementalCompilationConfiguration.BACKUP_CLASSES] = true
            ic[JvmSnapshotBasedIncrementalCompilationConfiguration.FORCE_RECOMPILATION] = force
            builder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = ic.build()
            val result = session.executeOperation(builder.build(), toolchains.createInProcessExecutionPolicy(), logger)
            when (result) {
                CompilationResult.COMPILATION_SUCCESS -> {
                    compiled = now
                    true
                }
                CompilationResult.COMPILATION_ERROR -> false
                else -> {
                    log.warn("Incremental compilation failed ($result); compiling the whole way")
                    forget()
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Incremental compilation failed: $e; compiling the whole way")
            forget()
            null
        }
    }

    // A snapshot of what a classpath entry declares (how the compiler tells what changed in
    // it). Jars are snapshot once and kept on disk while they stay the same; folders every time.
    private fun snapshotOf(entry: Path): Path {
        Files.createDirectories(snapshots)
        val folder = entry.isDirectory()
        val stamp = if (folder || !entry.exists()) "" else "${entry.getLastModifiedTime().toMillis()}-${entry.fileSize()}"
        val name = MessageDigest.getInstance("SHA-256").digest("$entry $stamp".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val file = snapshots.resolve("$name.bin")
        if (!folder && file.exists()) return file
        val started = System.nanoTime()
        val snapshot = session.executeOperation(jvm.classpathSnapshottingOperationBuilder(entry).build(), toolchains.createInProcessExecutionPolicy(), logger)
        snapshot.saveSnapshot(file)
        log.trace { "Snapshot of ${entry.fileName} in ${(System.nanoTime() - started) / 1_000_000} ms" }
        return file
    }

    // After a failure: the next compile starts from nothing.
    private fun forget() {
        compiled = null
        home.toFile().deleteRecursively()
    }

    // What the compiler tells besides its messages (those come through the renderer).
    private val logger = object : KotlinLogger {
        override val isDebugEnabled: Boolean get() = log.tracing
        override fun error(msg: String, throwable: Throwable?) = log.trace { "Kotlin: $msg${throwable?.let { " — $it" } ?: ""}" }
        override fun warn(msg: String, throwable: Throwable?) = log.trace { "Kotlin: $msg" }
        override fun info(msg: String) = log.trace { "Kotlin: $msg" }
        override fun debug(msg: String) = log.trace { "Kotlin: $msg" }
        override fun lifecycle(msg: String) = log.trace { "Kotlin: $msg" }
    }

    override fun close() {
        session.close()
    }
}
