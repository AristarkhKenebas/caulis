package dev.caulis.wm

import dev.caulis.log.Logger

/**
 * Starts programs the Wayland way first: toolkits that can do both (GTK, Qt, Electron,
 * Firefox) are told to prefer Wayland and fall back to X11 themselves. A program that still
 * fails — it connected to the compositor, then quit with an error soon after starting, before
 * it opened any window — is started again through X11 (Xwayland), and from then on started
 * that way right away. A program that never connected (a command-line tool, a script) is never
 * started twice: running it again would repeat what it did.
 *
 * [environment] fills in what every program gets (WAYLAND_DISPLAY, DISPLAY, the cursor, ...),
 * [userEnvironment] the user's own variables, put on last so they win over Caulis's choices;
 * [x11Available] tells whether X11 programs can run now; results of programs arrive on
 * other threads and are handed to [post] (the compositor's thread).
 */
internal class Launcher(
    private val environment: (MutableMap<String, String>) -> Unit,
    private val userEnvironment: (MutableMap<String, String>) -> Unit,
    private val x11Available: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val settings: () -> Settings,
) {
    /**
     * What the user chose: prefer Wayland (and fall back), programs that always go through
     * X11; the screen's [scale], and whether whole-scale programs get an X server of their own
     * ([sharpWholeScales]: then they draw at the next whole scale up and are shown smaller).
     */
    class Settings(
        val waylandFirst: Boolean,
        val x11Programs: Set<String>,
        val scale: Double = 1.0,
        val sharpWholeScales: Boolean = false,
        /** Where launched programs' output goes, and the file for [ProgramOutput.FILE]. */
        val output: ProgramOutput = ProgramOutput.DISCARD,
        val outputFile: java.nio.file.Path? = null,
        /** Whether a program that fails at once is started again through X11, and how soon it must fail. */
        val x11Fallback: Boolean = true,
        val x11FallbackMillis: Long = 3000,
    )

    private val log = Logger("clients")

    // Programs that needed X11 this session (by program name).
    private val neededX11 = HashSet<String>()

    // Programs started the Wayland way, until they showed a window or their time to fail passed.
    private class Started(val command: String, val pid: Long, val startedNanos: Long) {
        var showedWindow = false
        // It (or a child) connected to the compositor: a program of Wayland's, not a tool.
        var connected = false
    }

    private val watched = ArrayList<Started>()

    // Programs started that still run (asked to end when the compositor quits). Shared with the
    // threads that see them end.
    private val alive: MutableSet<Process> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Starts [command] (run by /bin/sh); [x11] forces X11. [fallback] false: never started
     * again through X11 (a screen locker). Its process id, or null if it could not start.
     */
    fun launch(command: String, x11: Boolean = false, fallback: Boolean = true): Long? {
        val program = programName(command)
        val settings = settings()
        val throughX = x11Available() && (x11 || program in settings.x11Programs || program in neededX11)
        return start(command, throughX, watch = fallback && !throughX && settings.waylandFirst && settings.x11Fallback && x11Available())
    }

    /** True if process [pid] is one of [roots] or a child (grandchild, ...) of one. */
    fun descendsFrom(pid: Int, roots: Set<Long>): Boolean = pid > 0 && roots.isNotEmpty() && ancestors(pid).any { it in roots }

    /** A client of process [pid] (or of one of its children) connected to the compositor. */
    fun clientConnected(pid: Int) {
        if (pid <= 0 || watched.isEmpty()) return
        val ancestors = ancestors(pid)
        for (started in watched) if (started.pid in ancestors) started.connected = true
    }

    /**
     * Asks every program started that still runs, and its children, to end (SIGTERM) — when
     * the compositor quits.
     */
    fun stopAll() {
        val running = alive.filter { it.isAlive }
        if (running.isEmpty()) return
        log.info { "Asking ${running.size} programs started inside to end" }
        for (process in running) {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
        alive.clear()
    }

    /**
     * Starts [command] (its words, no shell) the Wayland way, in [directory] (null: the
     * compositor's), its output with the compositor's and its input from the process's
     * outputStream: the process, or null if it cannot start.
     */
    fun spawn(command: List<String>, directory: java.io.File?): Process? {
        log.info { "Starting ${command.firstOrNull()?.substringAfterLast('/')}" }
        log.trace { "Its command: ${command.joinToString(" ")}" }
        return try {
            val builder = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            directory?.let(builder::directory)
            builder.environment().apply {
                environment(this)
                put("_JAVA_AWT_WM_NONREPARENTING", "1")
                javaScale(this)
                if (settings().waylandFirst) for ((name, value) in WAYLAND_FIRST) if (System.getenv(name) == null) put(name, value)
                userEnvironment(this) // (the user's own win)
            }
            dev.caulis.ffi.Processes.start(builder)
        } catch (e: Exception) {
            dev.caulis.ffi.Processes.tellStartFailed(log, command.firstOrNull()?.substringAfterLast('/').orEmpty(), e)
            null
        }
    }

    /** A window was opened by process [pid] (or one of its children): its program started fine. */
    fun windowOpened(pid: Int) {
        if (pid <= 0 || watched.isEmpty()) return
        val ancestors = ancestors(pid)
        for (started in watched) if (started.pid in ancestors) started.showedWindow = true
        watched.removeAll { it.showedWindow }
    }

    // Process [pid] and its parents, grandparents, ... (a few).
    private fun ancestors(pid: Int): Set<Long> =
        generateSequence(ProcessHandle.of(pid.toLong()).orElse(null)) { it.parent().orElse(null) }
            .take(MAX_DEPTH).map { it.pid() }.toSet()

    private fun start(command: String, throughX: Boolean, watch: Boolean): Long? {
        // (The command itself only at trace: it may hold a password or a token.)
        log.info { "Starting ${programName(command)}${if (throughX) " (through X11)" else ""}" }
        log.trace { "Its command: $command" }
        try {
            val process = startWith(ProcessBuilder("/bin/sh", "-c", command), programName(command), throughX, emptyMap())
            val started = Started(command, process.pid(), System.nanoTime())
            if (watch) watched += started
            process.onExit().thenAccept { ended -> post { ended(started, ended.exitValue(), throughX) } }
            return process.pid()
        } catch (e: Exception) {
            dev.caulis.ffi.Processes.tellStartFailed(log, programName(command), e)
            log.trace { "Its command: $command" }
            return null
        }
    }

    /**
     * Starts [command] (its words, no shell) as [launch] starts programs — the session's
     * environment with [extraEnvironment] on top, no input, the output where the settings say,
     * through X11 if it is one of x11Programs, ended with the others by [stopAll] — in
     * [directory] (null: the compositor's). Not started again through X11 when it fails (its
     * process is watched by whoever started it). The process, or null if it cannot start.
     */
    fun startProgram(command: List<String>, directory: java.io.File?, extraEnvironment: Map<String, String>): Process? {
        val program = command.firstOrNull()?.substringAfterLast('/').orEmpty()
        val throughX = x11Available() && (program in settings().x11Programs || program in neededX11)
        log.trace { "Its command: ${command.joinToString(" ")}" }
        return try {
            val builder = ProcessBuilder(command)
            directory?.let(builder::directory)
            startWith(builder, program, throughX, extraEnvironment)
        } catch (e: Exception) {
            dev.caulis.ffi.Processes.tellStartFailed(log, program, e)
            null
        }
    }

    // Starts [builder] the way programs inside are started (see startProgram): its process.
    private fun startWith(builder: ProcessBuilder, program: String, throughX: Boolean, extraEnvironment: Map<String, String>): Process {
        // (Its input is nothing: a program that reads it ends instead of waiting for ever.)
        builder.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        builder.environment().apply {
            environment(this)
            put("_JAVA_AWT_WM_NONREPARENTING", "1") // Java on X11: windows are not inside frames here
            javaScale(this)
            if (!throughX && settings().waylandFirst) {
                // The user's own choices (set before the compositor started) stay.
                for ((name, value) in WAYLAND_FIRST) if (System.getenv(name) == null) put(name, value)
            }
            // The user's own variables win over Caulis's choices...
            userEnvironment(this)
            putAll(extraEnvironment)
            // ...except that a program going through X11 must not find Wayland.
            if (throughX) {
                remove("WAYLAND_DISPLAY")
                remove("WAYLAND_SOCKET")
                remove("NIXOS_OZONE_WL")
                putAll(X11)
            }
        }
        val output = settings().let { it.output to it.outputFile }
        when (output.first) {
            ProgramOutput.DISCARD -> builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            ProgramOutput.LOG -> builder.redirectErrorStream(true)
            ProgramOutput.FILE -> {
                val file = checkNotNull(output.second) { "no file for the programs' output" }
                file.parent?.let(java.nio.file.Files::createDirectories)
                // (Yours alone, as the compositor's own log: programs may write what is private.)
                if (!java.nio.file.Files.exists(file)) {
                    runCatching {
                        java.nio.file.Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
                    }
                }
                builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(file.toFile()))
            }
        }
        val process = dev.caulis.ffi.Processes.start(builder)
        if (output.first == ProgramOutput.LOG) logOutput(program, process)
        alive += process
        process.onExit().thenAccept { ended -> alive -= ended }
        return process
    }

    // Each line [process] writes goes to the log, with [program]'s name (on a thread of its own).
    private fun logOutput(program: String, process: Process) {
        Thread.ofVirtual().name("caulis-output-$program").start {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> log.info { "$program: $line" } } }
            }
        }
    }

    // Java (Swing, AWT on X11) reads neither Xft.dpi nor the X cursor size, only its own
    // scale — and in whole numbers only (on X11 it has no fractional scale at all, not even
    // with -Dsun.java2d.uiScale). With an X server of its own it draws at the next whole one
    // up (1.5 -> 2) and is shown smaller, sharp and the right size; else at the nearest.
    private fun javaScale(env: MutableMap<String, String>) {
        if (System.getenv("J2D_UISCALE") != null) return
        val settings = settings()
        val whole = if (settings.sharpWholeScales) Math.ceil(settings.scale - 1e-9).toInt() else Math.round(settings.scale).toInt()
        if (whole > 1) env["J2D_UISCALE"] = whole.toString() else env.remove("J2D_UISCALE")
    }

    private fun ended(started: Started, status: Int, throughX: Boolean) {
        val wasWatched = watched.remove(started)
        // (Its name only, above trace: the command may hold a password or a token.)
        val program = programName(started.command)
        when {
            status == NOT_FOUND -> log.warn("$program could not be started: the program was not found (is it installed?)")
            status == NOT_EXECUTABLE -> log.warn("$program could not be started: it is not a program one can run")
            throughX || !wasWatched || started.showedWindow -> log.trace { "\"${started.command}\" ended (status $status)" }
            status == 0 || status in ENDED_BY_USER -> log.trace { "\"${started.command}\" ended (status $status)" }
            System.nanoTime() - started.startedNanos > settings().x11FallbackMillis * 1_000_000 -> log.trace { "\"${started.command}\" ended (status $status)" }
            // Never connected: a tool, not a program with windows — its failure is its own.
            !started.connected -> log.info { "$program ended (status $status)" }
            // A script (several commands, redirections): its failure is its own business —
            // running it again would repeat what it did.
            SHELL_SYNTAX.containsMatchIn(started.command) -> log.info { "$program ended (status $status)" }
            !x11Available() -> log.info { "$program quit at once (status $status); X11 is off, so it is not tried that way" }
            else -> {
                neededX11 += program
                log.info {
                    "$program quit at once on Wayland (status $status): starting it through X11. " +
                        "To start it that way right away: x11Programs += \"$program\""
                }
                start(started.command, throughX = true, watch = false)
            }
        }
        if (status == NOT_FOUND || status == NOT_EXECUTABLE) log.trace { "Its command: ${started.command}" }
    }

    companion object {
        private val SHELL_SYNTAX = Regex("""[;&|<>`$]""")
        private const val MAX_DEPTH = 16
        private const val NOT_FOUND = 127
        private const val NOT_EXECUTABLE = 126
        private val ENDED_BY_USER = setOf(130, 137, 143) // Ctrl+C, killed, terminated

        /** Toolkits prefer Wayland and fall back to X11 by themselves where they can. */
        val WAYLAND_FIRST = mapOf(
            "GDK_BACKEND" to "wayland,x11",
            "QT_QPA_PLATFORM" to "wayland;xcb",
            "ELECTRON_OZONE_PLATFORM_HINT" to "auto",
            "MOZ_ENABLE_WAYLAND" to "1",
            "NIXOS_OZONE_WL" to "1",
            "XDG_SESSION_TYPE" to "wayland",
        )

        /** Everything through X11. */
        val X11 = mapOf(
            "GDK_BACKEND" to "x11",
            "QT_QPA_PLATFORM" to "xcb",
            "ELECTRON_OZONE_PLATFORM_HINT" to "x11",
            "MOZ_ENABLE_WAYLAND" to "0",
            "SDL_VIDEODRIVER" to "x11",
            "XDG_SESSION_TYPE" to "x11",
        )

        /** The program a command starts: "env FOO=1 /usr/bin/steam -silent" → "steam". */
        fun programName(command: String): String {
            val words = command.trim().split(Regex("\\s+")).dropWhile { it == "env" || it == "exec" || it == "nohup" || '=' in it }
            return words.firstOrNull()?.substringAfterLast('/').orEmpty()
        }
    }
}
