package dev.caulis.ffi

import dev.caulis.log.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * The one way Caulis — and a compositor built on it — starts programs: [start] takes a
 * ProcessBuilder as it is (command, directory, environment, where input and output go) and
 * gives back an ordinary [Process].
 *
 * Java starts programs through a helper program of its own (jspawnhelper). When the system
 * updates Java while the compositor runs, the new helper no longer fits the running Java and
 * every start fails until the compositor is restarted ("Failed to exec spawn helper ... JDK
 * version mismatch"). So by default ([Method.NATIVE]) programs are started directly with the C
 * library's posix_spawn: nothing of Java's installation is needed. Only the program's
 * standard input, output and error go along (none of the compositor's other open files), it
 * starts with no signal blocked, and every signal at its default.
 *
 * [Method.JAVA] uses ProcessBuilder.start() — and when that fails with the helper's error,
 * the start is tried again natively, and natively from then on.
 */
object Processes {

    /** How programs are started. */
    enum class Method {
        /** With posix_spawn directly (the default): unaffected by Java being updated meanwhile. */
        NATIVE,

        /** With Java's own ProcessBuilder.start() (natively after its helper failed once). */
        JAVA,
    }

    private val log = Logger("clients")

    /** How programs are started from now on (see [Method]). */
    @JvmStatic
    @Volatile
    var method: Method = Method.NATIVE

    // What was said once already (a reason is logged once, not at every start).
    private val toldOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Starts [builder]'s command as [ProcessBuilder.start] would. Throws an IOException when
     * it cannot start; [isNotFound] tells whether that was because the program is not there.
     */
    @JvmStatic
    fun start(builder: ProcessBuilder): Process {
        checkJava()
        if (method == Method.JAVA) {
            try {
                return builder.start()
            } catch (e: IOException) {
                if (!isSpawnHelperFailure(e)) throw e
                // Java's helper fails: Java was updated under us. Natively from now on.
                method = Method.NATIVE
                log.warn("Java's way of starting programs fails (${firstLine(e.message)}): programs are started natively from now on")
                javaWatch.check()
            }
        }
        return try {
            startNatively(builder)
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            // (No native access, an old C library: Java's own way, from now on.)
            if (e is VirtualMachineError && e !is StackOverflowError) throw e
            method = Method.JAVA
            log.warn("Programs cannot be started natively ($e): Java's own way is used from now on")
            builder.start()
        }
    }

    /** True when [e] (from [start]) says the program is not there (not installed, not on PATH). */
    @JvmStatic
    fun isNotFound(e: Throwable?): Boolean = when (e) {
        null -> false
        is ProgramStartException -> e.notFound
        // ProcessBuilder's own: `Cannot run program "x": error=2, No such file or directory`.
        else -> e.message?.contains("error=2,") == true || isNotFound(e.cause)
    }

    /** Why [e] (from [start]) happened, in one short line. */
    @JvmStatic
    fun reason(e: Throwable): String = when {
        e is ProgramStartException -> e.reason
        isSpawnHelperFailure(e) -> "Java's helper for starting programs failed (Java was probably updated: restart)"
        else -> firstLine(e.message) ?: e.toString()
    }

    /**
     * Logs ([warn]) that [program] could not be started, saying the real reason: "is not
     * installed" only when it is not there. The same reason is said once for each program (the
     * next ones at trace), so a key pressed many times does not fill the log.
     */
    @JvmStatic
    @JvmOverloads
    fun tellStartFailed(logger: Logger, program: String, e: Throwable, needed: String? = null) {
        val text = if (isNotFound(e)) "$program is not installed" + (needed?.let { ": $it" } ?: "")
        else "$program could not be started: ${reason(e)}"
        if (toldOnce.add("$program\u0000${reason(e)}")) logger.warn(text) else logger.trace { text }
    }

    /**
     * True once the Java installation this program runs on was changed on disk since it
     * started (a system update): some of it (its helpers, libraries not loaded yet) may no
     * longer fit — the compositor is best restarted when convenient.
     */
    @JvmStatic
    fun javaChanged(): Boolean = javaWatch.check()

    /**
     * Calls [listener] once (on the thread that noticed it) when [javaChanged] becomes true —
     * looked at whenever a program is started, at most every few seconds. Close to stop.
     */
    @JvmStatic
    fun onJavaChanged(listener: Runnable): AutoCloseable {
        javaWatch.listeners += listener
        return AutoCloseable { javaWatch.listeners -= listener }
    }

    private fun checkJava() {
        runCatching { javaWatch.checkNowAndThen() }
    }

    private val javaWatch: JavaWatch by lazy { JavaWatch(Path.of(System.getProperty("java.home", "/nonexistent"))) }

    internal fun isSpawnHelperFailure(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            val m = t.message.orEmpty()
            if ("spawn helper" in m || "jspawnhelper" in m) return true
            t = t.cause
        }
        return false
    }

    private fun firstLine(text: String?): String? = text?.lineSequence()?.firstOrNull()?.trim()

    // --- starting natively -------------------------------------------------------------

    private const val O_RDONLY = 0
    private const val O_WRONLY = 1
    private const val O_CREAT = 0x40
    private const val O_TRUNC = 0x200
    private const val O_APPEND = 0x400
    private const val O_CLOEXEC = 0x80000
    internal const val ENOENT = 2
    internal const val EACCES = 13

    /** The program [name] stands for: itself if it has a slash, else the first executable file of that name on [path]. */
    internal fun locate(name: String, path: String?, directory: File?): Path {
        if ('/' in name) {
            val file = if (name.startsWith("/") || directory == null) Path.of(name) else directory.toPath().resolve(name)
            if (!Files.exists(file)) throw ProgramStartException(name, "No such file or directory", notFound = true)
            return Path.of(name)
        }
        var denied = false
        for (dir in (path ?: "/usr/local/bin:/usr/bin:/bin").split(':')) {
            val file = (if (dir.isEmpty()) Path.of(".") else Path.of(dir)).resolve(name)
            if (Files.isRegularFile(file)) {
                if (Files.isExecutable(file)) return file
                denied = true
            }
        }
        if (denied) throw ProgramStartException(name, "Permission denied", notFound = false)
        throw ProgramStartException(name, "No such file or directory", notFound = true)
    }

    internal fun startNatively(builder: ProcessBuilder): Process {
        val command = builder.command().toList()
        require(command.isNotEmpty()) { "No program to start" }
        val name = command.first()
        val environment = builder.environment().toMap()
        val directory = builder.directory()
        if (directory != null && !directory.isDirectory) {
            throw ProgramStartException(name, "the directory $directory does not exist", notFound = false)
        }
        val program = locate(name, environment["PATH"] ?: System.getenv("PATH"), directory)

        // Ours to close after the start: the ends the program gets, and files opened for it.
        val theirs = ArrayList<Fd>()
        // Ours to keep: the ends we write to and read from.
        var stdin: Fd? = null
        var stdout: Fd? = null
        var stderr: Fd? = null
        val handOver = LinkedHashMap<Int, Fd>()
        fun open(file: File, flags: Int): Fd = Arena.ofConfined().use { arena ->
            try {
                Fd.adopt(Libc.open(arena.allocateFrom(file.path), flags or O_CLOEXEC, 0x1b6))
            } catch (e: NativeException) {
                throw IOException("$file: ${Libc.describeError(e.errno)}")
            }
        }
        fun output(redirect: ProcessBuilder.Redirect, target: Int): Fd? = when (redirect.type()) {
            ProcessBuilder.Redirect.Type.PIPE -> Fd.pipe().let { (read, write) -> theirs += write; handOver[target] = write; read }
            ProcessBuilder.Redirect.Type.WRITE -> open(redirect.file(), O_WRONLY or O_CREAT or O_TRUNC).also { theirs += it; handOver[target] = it }.let { null }
            ProcessBuilder.Redirect.Type.APPEND -> open(redirect.file(), O_WRONLY or O_CREAT or O_APPEND).also { theirs += it; handOver[target] = it }.let { null }
            else -> null // INHERIT (READ is not for output)
        }
        try {
            when (builder.redirectInput().type()) {
                ProcessBuilder.Redirect.Type.PIPE -> Fd.pipe().let { (read, write) -> theirs += read; handOver[0] = read; stdin = write }
                ProcessBuilder.Redirect.Type.READ -> open(builder.redirectInput().file(), O_RDONLY).also { theirs += it; handOver[0] = it }
                else -> {}
            }
            stdout = output(builder.redirectOutput(), 1)
            if (!builder.redirectErrorStream()) stderr = output(builder.redirectError(), 2)
            val pid = spawn(program, command, environment, directory, handOver, errorToOutput = builder.redirectErrorStream())
            log.trace { "Started ${program.fileName} natively (pid $pid)" }
            return NativeProcess(pid, stdin, stdout, stderr)
        } catch (e: Throwable) {
            stdin?.close()
            stdout?.close()
            stderr?.close()
            throw e
        } finally {
            theirs.forEach { runCatching { it.close() } }
        }
    }

    private fun spawn(program: Path, command: List<String>, environment: Map<String, String>, directory: File?, handOver: Map<Int, Fd>, errorToOutput: Boolean): Int =
        Arena.ofConfined().use { arena ->
            fun strings(values: List<String>): MemorySegment {
                val array = arena.allocate(ADDRESS, values.size + 1L)
                values.forEachIndexed { i, value -> array.setAtIndex(ADDRESS, i.toLong(), arena.allocateFrom(value)) }
                array.setAtIndex(ADDRESS, values.size.toLong(), MemorySegment.NULL)
                return array
            }
            val actions = arena.allocate(Libc.SPAWN_ACTIONS_SIZE, 16)
            val attributes = arena.allocate(Libc.SPAWN_ATTR_SIZE, 16)
            Libc.spawnActionsInit(actions)
            try {
                Libc.spawnAttrClean(attributes, arena.allocate(2 * Libc.SIGSET_SIZE, 16))
                try {
                    // Each source first onto a number above all used ones, then onto its target: a
                    // source that has another one's target number is not overwritten meanwhile.
                    val entries = handOver.entries.toList()
                    val above = (entries.map { it.key } + entries.map { it.value.value }).maxOrNull()?.plus(1) ?: 3
                    entries.forEachIndexed { i, (_, fd) -> Libc.spawnActionsDup2(actions, fd.value, above + i) }
                    entries.forEachIndexed { i, (target, _) -> Libc.spawnActionsDup2(actions, above + i, target) }
                    if (errorToOutput) Libc.spawnActionsDup2(actions, 1, 2)
                    // Nothing else of ours goes along (the JVM keeps files open that are not close-on-exec).
                    Libc.spawnActionsCloseFrom(actions, 3)
                    directory?.let { Libc.spawnActionsChdir(actions, arena.allocateFrom(it.path)) }
                    try {
                        Libc.spawnAt(
                            arena.allocateFrom(program.toString()), actions, attributes,
                            strings(command), strings(environment.map { (k, v) -> "$k=$v" }), arena.allocate(JAVA_INT),
                        )
                    } catch (e: NativeException) {
                        throw ProgramStartException(command.first(), Libc.describeError(e.errno), notFound = e.errno == ENOENT)
                    }
                } finally {
                    Libc.spawnAttrDestroy(attributes)
                }
            } catch (e: NativeException) {
                throw IOException("Cannot run program \"${command.first()}\": ${e.message}", e)
            } finally {
                Libc.spawnActionsDestroy(actions)
            }
        }
}

/**
 * A program could not be started: [reason] says why; [notFound] when it is not there (not
 * installed, not on PATH) rather than failing for another reason.
 */
class ProgramStartException(
    val program: String,
    val reason: String,
    val notFound: Boolean,
) : IOException("Cannot run program \"$program\": $reason")

// Whether the Java installation under us changed on disk (its files replaced by an update).
internal class JavaWatch(private val home: Path) {
    private fun snapshot(): List<Any?> = FILES.map { name ->
        runCatching {
            val attributes = Files.readAttributes(home.resolve(name), java.nio.file.attribute.BasicFileAttributes::class.java)
            listOf(attributes.fileKey(), attributes.lastModifiedTime().toMillis(), attributes.size())
        }.getOrNull()
    }

    private val atStart = snapshot()
    @Volatile private var changed = false
    @Volatile private var checkedAt = 0L
    val listeners = CopyOnWriteArrayList<Runnable>()

    /** Compares with the start now: true once changed (then the listeners hear it, once). */
    fun check(): Boolean {
        if (changed) return true
        checkedAt = System.nanoTime()
        if (snapshot() == atStart) return false
        synchronized(this) {
            if (changed) return true
            changed = true
        }
        Logger("clients").warn("The Java this program runs on was updated ($home): restart it when convenient")
        for (listener in listeners) runCatching { listener.run() }.onFailure { Logger("clients").warn("A listener of Java's update failed: $it") }
        return true
    }

    /** [check], but at most every few seconds (it is asked at each program start). */
    fun checkNowAndThen(): Boolean {
        if (changed) return true
        if (checkedAt != 0L && System.nanoTime() - checkedAt < EVERY_NANOS) return false
        return check()
    }

    companion object {
        // What an update replaces: the classes, the helper that starts programs, the version file.
        val FILES = listOf("lib/modules", "lib/jspawnhelper", "release")
        val EVERY_NANOS: Long = TimeUnit.SECONDS.toNanos(10)
    }
}

// A program started with posix_spawn, as a java.lang.Process: one daemon thread waits for its end.
internal class NativeProcess(private val pid: Int, stdin: Fd?, stdout: Fd?, stderr: Fd?) : Process() {
    private val lock = Any()
    private var reaped = false
    private val exit = CompletableFuture<Int>()
    private val input: OutputStream = stdin?.let(::FdOutputStream) ?: NullOutput
    private val output: InputStream = stdout?.let(::FdInputStream) ?: InputStream.nullInputStream()
    private val error: InputStream = stderr?.let(::FdInputStream) ?: InputStream.nullInputStream()

    init {
        reapers.execute(::reap)
    }

    // Waits without reaping (the pid stays ours while a signal may still be sent to it), then reaps.
    private fun reap() {
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(128)
            while (true) {
                try {
                    Libc.waitid(pid, info, WEXITED or WNOWAIT)
                    break
                } catch (e: NativeException) {
                    if (e.errno != Errno.EINTR) break
                }
            }
            val status = arena.allocate(JAVA_INT)
            val code = synchronized(lock) {
                val result = runCatching { Libc.waitpid(pid, status, 0) }.getOrDefault(-1)
                reaped = true
                if (result != pid) 128 + 9 else {
                    // WIFEXITED / WEXITSTATUS / WTERMSIG, as glibc defines them (signal n: 128 + n, as Java says it).
                    val value = status.get(JAVA_INT, 0)
                    if (value and 0x7f == 0) (value shr 8) and 0xff else 128 + (value and 0x7f)
                }
            }
            exit.complete(code)
        }
    }

    private fun signal(number: Int) = synchronized(lock) {
        if (!reaped) runCatching { Libc.kill(pid, number) }
    }

    override fun getOutputStream(): OutputStream = input
    override fun getInputStream(): InputStream = output
    override fun getErrorStream(): InputStream = error
    override fun waitFor(): Int = exit.get()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try {
        exit.get(timeout, unit)
        true
    } catch (_: java.util.concurrent.TimeoutException) {
        false
    }
    override fun exitValue(): Int = exit.getNow(null) ?: throw IllegalThreadStateException("process hasn't exited")
    override fun destroy() = signal(SIGTERM)
    override fun destroyForcibly(): Process = apply { signal(SIGKILL) }
    override fun supportsNormalTermination(): Boolean = true
    override fun isAlive(): Boolean = !exit.isDone
    override fun pid(): Long = pid.toLong()
    override fun onExit(): CompletableFuture<Process> = exit.thenApply { this }
    override fun toHandle(): ProcessHandle = ProcessHandle.of(pid.toLong()).orElseThrow { UnsupportedOperationException("process $pid has ended") }
    override fun children(): Stream<ProcessHandle> = ProcessHandle.of(pid.toLong()).map { it.children() }.orElse(Stream.empty())
    override fun descendants(): Stream<ProcessHandle> = ProcessHandle.of(pid.toLong()).map { it.descendants() }.orElse(Stream.empty())
    override fun info(): ProcessHandle.Info = toHandle().info()
    override fun toString(): String = "Process[pid=$pid, exitValue=${exit.getNow(null) ?: "\"not exited\""}]"

    private companion object {
        const val WEXITED = 4
        const val WNOWAIT = 0x01000000
        const val SIGTERM = 15
        const val SIGKILL = 9
        val reapers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("caulis-process-reaper").stackSize(256 * 1024).factory())
    }
}

// Platform threads for what would wait in C on a virtual thread (reading or writing a pipe).
private val waiters = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("caulis-process-io").factory())

private fun <T> offCarrier(work: () -> T): T = try {
    waiters.submit(java.util.concurrent.Callable { work() }).get()
} catch (e: java.util.concurrent.ExecutionException) {
    throw (e.cause as? IOException) ?: IOException(e.cause)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw java.io.InterruptedIOException("interrupted")
}

private object NullOutput : OutputStream() {
    override fun write(b: Int) = throw IOException("Stream closed")
}

// Reading a pipe from the program (its output), through the C library.
private class FdInputStream(private val fd: Fd) : InputStream() {
    @Volatile private var closed = false

    private fun raw(): Int = if (closed) throw IOException("Stream closed") else try { fd.value } catch (_: IllegalStateException) { throw IOException("Stream closed") }

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val n = read(one, 0, 1)
            if (n < 0) return -1
            if (n == 1) return one[0].toInt() and 0xff
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        java.util.Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        // A virtual thread would hold its carrier for the whole wait in C: it waits for a
        // platform thread that reads instead (and its carrier goes on with other work).
        if (Thread.currentThread().isVirtual) return offCarrier {
            val chunk = ByteArray(minOf(len, CHUNK))
            readHere(chunk, 0, chunk.size).also { n -> if (n > 0) System.arraycopy(chunk, 0, b, off, n) }
        }
        return readHere(b, off, len)
    }

    private fun readHere(b: ByteArray, off: Int, len: Int): Int {
        val number = raw()
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(minOf(len, CHUNK).toLong())
            while (true) {
                val n = try {
                    Libc.read(number, buffer)
                } catch (e: NativeException) {
                    if (e.errno == Errno.EINTR) continue
                    if (closed) throw IOException("Stream closed")
                    throw IOException(e.message)
                }
                if (n == 0L) return -1
                MemorySegment.copy(buffer, JAVA_BYTE, 0, b, off, n.toInt())
                return n.toInt()
            }
        }
    }

    override fun available(): Int = if (closed) 0 else try { Libc.ioctlInt(raw(), FIONREAD) } catch (_: Exception) { 0 }

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            fd.close()
        }
    }

    private companion object {
        const val CHUNK = 65536
        const val FIONREAD = 0x541BL
    }
}

// Writing into a pipe to the program (its input).
private class FdOutputStream(private val fd: Fd) : OutputStream() {
    @Volatile private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        java.util.Objects.checkFromIndexSize(off, len, b.size)
        if (closed) throw IOException("Stream closed")
        // (A full pipe waits in C: not on a virtual thread's carrier, see FdInputStream.)
        if (Thread.currentThread().isVirtual) {
            val copy = b.copyOfRange(off, off + len)
            offCarrier { writeHere(copy, 0, copy.size) }
            return
        }
        writeHere(b, off, len)
    }

    private fun writeHere(b: ByteArray, off: Int, len: Int) {
        Arena.ofConfined().use { arena ->
            val data = arena.allocate(len.toLong().coerceAtLeast(1))
            MemorySegment.copy(b, off, data, JAVA_BYTE, 0, len)
            var done = 0L
            while (done < len) {
                done += try {
                    Libc.write(fd.value, data.asSlice(done))
                } catch (e: NativeException) {
                    if (e.errno == Errno.EINTR) continue
                    throw IOException(e.message)
                } catch (_: IllegalStateException) {
                    throw IOException("Stream closed")
                }
            }
        }
    }

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            fd.close()
        }
    }
}
