package dev.caulis.log

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reports of what stopped the compositor (or one of its threads): a text file that says what
 * failed and where, on what system, with what the log had just before. Secrets, names and
 * titles are taken out ([Redactor]), so a report can be sent as it is.
 *
 * Set [folder] to have them written; [Log] alone writes none.
 */
object CrashReports {

    /** Where reports go (made if needed); null: none are written, only the log has it. */
    @Volatile
    @JvmStatic
    var folder: Path? = null

    /** How many lines of the log a report takes along. */
    @Volatile
    @JvmStatic
    var lastLines: Int = 200
        set(value) {
            require(value >= 0) { "lastLines is 0 or more, not $value" }
            field = value
        }

    /** How many reports stay in [folder]; older ones go. */
    @Volatile
    @JvmStatic
    var keep: Int = 10
        set(value) {
            require(value >= 1) { "keep is 1 or more, not $value" }
            field = value
        }

    /** Asked at the time of a report for the words to take out of it: the open windows' titles, ... */
    @Volatile
    @JvmStatic
    var privateWords: () -> Collection<String> = { emptyList() }

    /** Asked at the time of a report for the compositor's state, in words; null: none. */
    @Volatile
    @JvmStatic
    var state: () -> String? = { null }

    private val log = Logger("crash")
    private val facts = java.util.concurrent.ConcurrentSkipListMap<String, String>()

    /**
     * A line every report has, e.g. fact("newm", "0.5.1"), fact("GPU", renderer). Known only at
     * run time: asked of the one who knows it, kept until the report.
     */
    @JvmStatic
    fun fact(name: String, value: String) {
        facts[name] = value
    }

    /** What is known of this system: [fact]s, and what can be read of the JDK, kernel and GPUs. */
    @JvmStatic
    fun facts(): Map<String, String> {
        val all = java.util.TreeMap(facts)
        all["JDK"] = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}"
        readFirstLine("/proc/sys/kernel/osrelease")?.let { all["Kernel"] = it }
        distribution()?.let { all["System"] = it }
        all.putIfAbsent("GPU (seen in /sys)", gpus())
        all["Memory of this program"] = "${Runtime.getRuntime().totalMemory() / MB} MB heap, at most ${Runtime.getRuntime().maxMemory() / MB} MB"
        all["Session"] = listOf("XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "CAULIS_MODE", "CAULIS_LOG").mapNotNull { name -> System.getenv(name)?.let { "$name=$it" } }.joinToString(" ").ifEmpty { "(no variables)" }
        return all
    }

    /** The text of a report; [error] may be null when [what] says all. Already cleaned. */
    @JvmStatic
    @JvmOverloads
    fun text(what: String, error: Throwable? = null, thread: Thread? = null, stacks: Boolean = false): String {
        val text = buildString {
            append("# Crash report\n\nWhat happened: ").append(what).append('\n')
            append("When: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n')
            if (thread != null) append("Thread: ").append(thread.name).append('\n')
            append("\n## System\n")
            for ((name, value) in facts()) append(name).append(": ").append(value).append('\n')
            if (error != null) {
                append("\n## The error\n")
                append(java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) })
            }
            if (stacks) {
                append("\n## Every thread\n")
                for ((t, frames) in Thread.getAllStackTraces()) {
                    append(t.name).append(" (").append(t.state).append(")\n")
                    frames.take(STACK_FRAMES).forEach { append("    at ").append(it).append('\n') }
                }
            }
            val state = quietly(null) { state() }
            if (state != null) append("\n## The state\n").append(state).append('\n')
            val lines = Log.recent().takeLast(lastLines)
            append("\n## The last ").append(lines.size).append(" lines of the log\n")
            lines.forEach { append(it).append('\n') }
        }
        return Redactor(quietly(emptyList()) { privateWords() }).clean(text)
    }

    /**
     * Writes a report to [folder], from any thread; never throws (this is what runs when all
     * else went wrong). The file's path, or null when none is written.
     */
    @JvmStatic
    @JvmOverloads
    fun write(what: String, error: Throwable? = null, thread: Thread? = null, stacks: Boolean = false): Path? {
        val dir = folder ?: return null
        return try {
            val body = try {
                text(what, error, thread, stacks)
            } catch (e: Throwable) {
                // (Out of memory, perhaps: the short one, which needs nothing.)
                Redactor().clean("# Crash report\n\nWhat happened: $what\n$error\n(The full report failed: $e)\n")
            }
            Files.createDirectories(dir)
            val file = dir.resolve("crash-" + LocalDateTime.now().format(FILE_TIME) + "-" + serial.incrementAndGet() + ".txt")
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            Files.writeString(file, body)
            tidy(dir)
            log.error("A report of this is in $file")
            file
        } catch (e: Exception) {
            log.warn("The crash report could not be written to $dir: $e")
            null
        }
    }

    /** The reports in [folder] (or [dir]), the newest last. */
    @JvmStatic
    @JvmOverloads
    fun list(dir: Path? = folder): List<Path> {
        if (dir == null || !Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { all ->
            all.filter { it.fileName.toString().startsWith("crash-") && it.fileName.toString().endsWith(".txt") }.toList()
        }.sortedBy { it.fileName.toString() }
    }

    // The oldest go.
    private fun tidy(dir: Path) {
        val all = list(dir)
        for (old in all.dropLast(keep)) Files.deleteIfExists(old)
    }

    private inline fun <T> quietly(otherwise: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        log.warn("A part of the crash report failed: $e")
        otherwise
    }

    private fun readFirstLine(path: String): String? = try {
        Files.readAllLines(Path.of(path)).firstOrNull()?.trim()
    } catch (_: java.io.IOException) {
        null // (Not this kind of system: not said.)
    }

    private fun distribution(): String? = try {
        Files.readAllLines(Path.of("/etc/os-release")).firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
    } catch (_: java.io.IOException) {
        null // (Not there: not said.)
    }

    // Each GPU as the kernel knows it: driver, vendor and device numbers.
    private fun gpus(): String = try {
        Files.newDirectoryStream(Path.of("/sys/class/drm"), "card[0-9]").use { cards ->
            cards.map { card ->
                val device = card.resolve("device")
                val driver = runCatching { Files.readSymbolicLink(device.resolve("driver")).fileName.toString() }.getOrDefault("no driver")
                "${card.fileName}: $driver ${readFirstLine("$device/vendor") ?: "?"}:${readFirstLine("$device/device") ?: "?"}"
            }.joinToString(", ").ifEmpty { "none" }
        }
    } catch (_: java.io.IOException) {
        "none seen"
    }

    private val serial = java.util.concurrent.atomic.AtomicInteger()
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val MB = 1024 * 1024
    private const val STACK_FRAMES = 40
}
