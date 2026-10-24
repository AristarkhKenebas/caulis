import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Generates Java FFM bindings for a C header with jextract.
 *
 * jextract itself is downloaded once into the Gradle cache and checked against a known
 * SHA-256, so a tampered download is never run.
 */
@CacheableTask
abstract class JextractTask : DefaultTask() {

    /** The C header to read (it may just #include other headers). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val header: RegularFileProperty

    /** Java package of the generated code. */
    @get:Input
    abstract val targetPackage: Property<String>

    /** Name of the generated class holding functions and constants. */
    @get:Input
    abstract val headerClassName: Property<String>

    /** Only these declarations are generated, e.g. "struct epoll_event", "macro EPOLLIN". */
    @get:Input
    abstract val includes: ListProperty<String>

    @get:Input
    abstract val jextractUrl: Property<String>

    @get:Input
    abstract val jextractSha256: Property<String>

    /** Where jextract is unpacked (outside the project). */
    @get:Internal
    abstract val toolsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val jextract = ensureJextract()
        val output = outputDir.get().asFile
        // Old files of this package are removed so deleted declarations do not linger.
        output.resolve(targetPackage.get().replace('.', '/')).deleteRecursively()

        val command = mutableListOf(
            jextract.absolutePath,
            "--output", output.absolutePath,
            "--target-package", targetPackage.get(),
            "--header-class-name", headerClassName.get(),
        )
        for (include in includes.get()) {
            // "struct epoll_event" -> --include-struct epoll_event
            val (kind, name) = include.split(' ', limit = 2)
            command += listOf("--include-$kind", name)
        }
        command += header.get().asFile.absolutePath

        run(command, "jextract failed")
    }

    private fun ensureJextract(): File {
        val home = toolsDir.get().asFile.resolve("jextract-" + jextractSha256.get().take(12))
        val binary = home.resolve("jextract-25/bin/jextract")
        if (binary.canExecute()) return binary

        home.mkdirs()
        val archive = home.resolve("jextract.tar.gz")
        logger.lifecycle("Downloading jextract from ${jextractUrl.get()}")
        download(jextractUrl.get(), archive)

        val actual = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != jextractSha256.get()) {
            archive.delete()
            error("jextract download has SHA-256 $actual, expected ${jextractSha256.get()}. Not running it.")
        }

        run(listOf("tar", "-xzf", archive.absolutePath, "-C", home.absolutePath), "Unpacking jextract failed")
        archive.delete()
        check(binary.canExecute()) { "jextract not found at $binary after unpacking" }
        return binary
    }

    // Downloads with timeouts; if the server goes silent, it retries and continues
    // from where it stopped instead of waiting forever.
    private fun download(url: String, target: File) {
        var lastError: Exception? = null
        repeat(5) { attempt ->
            try {
                val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                val already = if (target.exists()) target.length() else 0L
                if (already > 0) connection.setRequestProperty("Range", "bytes=$already-")
                val resumed = connection.responseCode == 206
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(target, resumed).use { input.copyTo(it) }
                }
                return
            } catch (e: java.io.IOException) {
                lastError = e
                logger.warn("Download attempt ${attempt + 1} failed (${e.message}), retrying")
            }
        }
        throw IllegalStateException("Could not download $url", lastError)
    }

    private fun run(command: List<String>, failure: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("$failure:\n$log")
        if (log.isNotBlank()) logger.info(log)
    }
}
