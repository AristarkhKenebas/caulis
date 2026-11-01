package dev.caulis.log

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Caulis's log. How much is written is a level, for everything or for single areas:
 *
 * - **0**: nearly nothing (only what makes the compositor stop);
 * - **1**: problems — errors and warnings (the default);
 * - **2**: what happens — windows opening and closing, monitors, layouts, locking, ...;
 * - **3**: everything, step by step — every Wayland message, every focus change, every key,
 *   every frame — to follow exactly what happened before something went wrong.
 *
 * Set it when starting, without changing code:
 *
 * ```
 * CAULIS_LOG=2 ./my-compositor                       # level 2 for everything
 * CAULIS_LOG="1,focus=3,windows=3" ./my-compositor   # level 1, but focus and windows in detail
 * CAULIS_LOG=3 CAULIS_LOG_FILE=/tmp/caulis.log ./my-compositor
 * ```
 *
 * or in code (`Log.level = 2`, `Log.setLevel("focus", 3)`, `Log.configure("1,wayland=3")`),
 * also while running. The areas are listed in [AREAS].
 *
 * A [Logger.trace] of a level that is off costs nothing: its text is not even made. A
 * [Logger.info] is kept in the black box ([recent]) even then, so it never goes on a path that
 * runs for every frame or every message: there, [Logger.trace] (or [Logger.tracing]).
 */
object Log {

    const val QUIET = 0
    const val PROBLEMS = 1
    const val EVENTS = 2
    const val EVERYTHING = 3

    /** The areas Caulis logs, and what each is about. */
    @JvmField
    val AREAS: Map<String, String> = linkedMapOf(
        "wayland" to "every message between programs and the compositor (level 3), like WAYLAND_DEBUG",
        "clients" to "programs connecting and leaving, their protocol errors",
        "windows" to "windows appearing, closing, their sizes, states and titles",
        "focus" to "which window or panel has the keyboard and the pointer",
        "input" to "keys, mouse buttons, movement, scrolling, keyboard layouts",
        "gestures" to "touchpad swipes and pinches",
        "layout" to "arranging windows: layouts, workspaces, tiling commands, glides",
        "layers" to "panels, docks, wallpapers and launchers (layer shell)",
        "lock" to "the screen lock",
        "plugins" to "plugins found, started, stopped, reloaded, failing; each plugin logs under plugin-<id>",
        "docks" to "what docks and taskbars are told and ask for",
        "cursor" to "cursor themes and shapes",
        "capture" to "screenshots and screen recordings: who takes pictures, of what",
        "clipboard" to "copy and paste: what is on the clipboard, who pastes it (also between X11 and Wayland)",
        "render" to "drawing frames: what is redrawn, how long it takes",
        "drm" to "monitors, their modes, showing frames on the screen",
        "session" to "the seat, text consoles, devices coming and going",
        "nested" to "running as a window inside another session",
        "xwayland" to "X11 programs: starting Xwayland, their windows and focus",
        "notices" to "messages of the compositor on screen (mistakes in the configuration, ...)",
        "reload" to "taking on changed code while running (hot reload): compiling, what changed",
        "config" to "your own code (key bindings, rules, events): its mistakes",
        "native" to "C libraries Caulis uses, the event loop and its watchdog",
        "autostart" to "programs started with the session: when each started, ended, was started again",
        "reveal" to "the start: the first frame, waiting until warm, the desktop fading in",
        "sound" to "the startup sound: made, played (by which player), or why not",
        // (Areas Caulis logs in as well: each a name log(area, level) and CAULIS_LOG take.)
        "keyboard" to "keyboard layouts and keymaps, what onKey handlers took",
        "keys" to "key bindings, a window keeping the keys (shortcuts inhibited), the laptop's keys",
        "monitors" to "displays: which monitors are used, mirrored or off (laptop and external screens)",
        "overlay" to "the compositor's own overlays (a screenshot chooser) opening and closing",
        "idle" to "idle: the screen going dark, what keeps it awake",
        "appearance" to "light and dark, accent colours, what programs are told of them (portal, GSettings)",
        "dbus" to "the compositor's D-Bus connections: names, calls, signals",
        "permissions" to "which programs may use screencopy, the clipboard and other features",
        "ipc" to "programs talking to the compositor over its socket (--do, scripts)",
        "loop" to "the event loop: tasks and timers that took long",
        "lua" to "a config.lua: loading it, its mistakes",
        "script" to "a config.kts: compiling it, its mistakes",
        "compose" to "Compose widgets: their scenes, drawing, failures",
        "screenshot" to "the screenshot tool: what was taken, where it went",
        "crash" to "reports of what stopped the compositor or one of its threads",
    )

    /** The level of every area that has none of its own. */
    @Volatile
    @JvmStatic
    var level: Int = PROBLEMS
        set(value) {
            field = value.coerceIn(QUIET, EVERYTHING)
            version++
        }

    private val areaLevels = ConcurrentHashMap<String, Int>()

    // Changes with every setting, so loggers can cache their level.
    @Volatile
    internal var version = 0L
        private set

    /** Gives [area] its own [level] (e.g. "focus" to 3), whatever the others have. */
    @JvmStatic
    fun setLevel(area: String, level: Int) {
        areaLevels[area] = level.coerceIn(QUIET, EVERYTHING)
        version++
    }

    /** Back to the common [level] for [area]. */
    @JvmStatic
    fun clearLevel(area: String) {
        areaLevels.remove(area)
        version++
    }

    /** The level [area] logs at now. */
    @JvmStatic
    fun levelOf(area: String): Int = areaLevels[area] ?: level

    /** The areas that have a level of their own (see [setLevel]), with it. */
    @JvmStatic
    fun areaLevels(): Map<String, Int> = areaLevels.toSortedMap()

    /**
     * Sets levels from text, as `CAULIS_LOG` does: "2" (everything at 2), "focus=3" (one
     * area), or both: "1,wayland=3,focus=3". Unknown parts are reported, not fatal.
     */
    @JvmStatic
    fun configure(text: String) {
        for (part in text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=').trim().toIntOrNull()
            when {
                value == null || value !in QUIET..EVERYTHING -> warnSetting("\"$part\" is not a log level (0 to 3)")
                name.isEmpty() -> level = value
                else -> {
                    if (name !in AREAS && !name.startsWith("plugin-")) warnSetting("there is no log area \"$name\" (the areas: ${AREAS.keys.joinToString()})")
                    setLevel(name, value)
                }
            }
        }
    }

    private fun warnSetting(problem: String) = write("log", PROBLEMS, "W", "Log setting ignored: $problem", null)

    // --- where lines go ------------------------------------------------------------------

    /** Write the log to the console (standard error). */
    @Volatile
    @JvmStatic
    var toConsole = true

    private var fileStream: PrintStream? = null
    private var fileBytes = 0L

    /** Also write the log to this file (appended), or null for none. `CAULIS_LOG_FILE` sets it too. */
    @JvmStatic
    var file: Path? = null
        @Synchronized set(value) {
            fileStream?.close()
            fileStream = value?.let(::openFile)
            field = value
        }

    /**
     * A log file that grows to this many bytes is moved aside (file.1, the one before to
     * file.2, ...) and begun anew; 0: it may grow without end.
     */
    @Volatile
    @JvmStatic
    var fileLimitBytes: Long = 16L * 1024 * 1024
        set(value) {
            require(value >= 0) { "fileLimitBytes is 0 or more, not $value" }
            field = value
        }

    /** How many files moved aside by [fileLimitBytes] are kept; the oldest goes (0: none is). */
    @Volatile
    @JvmStatic
    var fileKeep: Int = 2
        set(value) {
            require(value in 0..100) { "fileKeep is 0 to 100, not $value" }
            field = value
        }

    /**
     * The file gets at least this level for every area, whatever the console gets: with 2, a
     * crash that took the program with it (a native one, the power) still leaves what happened
     * up to then. 0 to 2 (level 3 only for the areas set to it); 0: as the console.
     */
    @Volatile
    @JvmStatic
    var fileLevel: Int = QUIET
        set(value) {
            require(value in QUIET..EVENTS) { "fileLevel is 0, 1 or 2, not $value" }
            field = value
        }

    // Opened for appending; readable by you alone (it may name programs, windows and commands),
    // made so before the first line.
    private fun openFile(path: Path): PrintStream {
        path.parent?.let(Files::createDirectories)
        if (!Files.exists(path)) {
            try {
                Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Made just now by someone else: appended to.
            } catch (_: UnsupportedOperationException) {
                // Not a POSIX file system: made below, with its usual rights.
            }
        }
        fileBytes = if (Files.exists(path)) Files.size(path) else 0L
        return PrintStream(Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true)
    }

    // The file is full: it moves aside and a new one begins. (Called holding the lock of Log.)
    // A move that fails (the disk, rights) must not stop anything: said once, and writing goes on.
    private fun rotateFile() {
        val path = file ?: return
        val keep = fileKeep
        fileStream?.close()
        try {
            if (keep == 0) {
                Files.deleteIfExists(path)
            } else {
                Files.deleteIfExists(rotated(path, keep))
                for (n in keep - 1 downTo 1) if (Files.exists(rotated(path, n))) Files.move(rotated(path, n), rotated(path, n + 1))
                Files.move(path, rotated(path, 1))
            }
        } catch (e: java.io.IOException) {
            System.err.println("The log file $path cannot be moved aside: $e")
            fileBytes = Long.MIN_VALUE / 2 // (no more tries in this run)
        }
        val before = fileBytes
        fileStream = try {
            openFile(path)
        } catch (e: java.io.IOException) {
            System.err.println("The log file $path cannot be written: $e")
            null
        }
        if (before < 0) fileBytes = before
    }

    private fun rotated(path: Path, number: Int): Path = path.resolveSibling("${path.fileName}.$number")

    /** Your own place for log lines (e.g. a window that shows them), besides console and file. */
    @Volatile
    @JvmStatic
    var listener: ((String) -> Unit)? = null

    /** One line of the log as it is written, for [watch]. */
    class Entry(val area: String, val level: Int, val message: String, val cause: Throwable?, private val mark: String) {
        /** Written with [Logger.error]. */
        val isError: Boolean get() = mark == "E"

        /** Written with [Logger.warn]. */
        val isWarning: Boolean get() = mark == "W"

        override fun toString() = "$mark $area $message"
    }

    private val watchers = java.util.concurrent.CopyOnWriteArrayList<(Entry) -> Unit>()

    /**
     * Runs [watcher] for every line the log gets (written or only kept, see [recent]) — on
     * the thread that logs it. Close the result to stop.
     */
    @JvmStatic
    fun watch(watcher: (Entry) -> Unit): AutoCloseable {
        watchers += watcher
        return AutoCloseable { watchers -= watcher }
    }

    // --- the black box: the last events, kept even when they are not written ----------------

    private const val RECENT_LINES = 1000
    private val recent = ArrayDeque<String>(RECENT_LINES)

    /**
     * The last [RECENT_LINES] lines up to level 2, kept in memory even when the log level is
     * lower — what happened just before a problem (Caulis adds them to its crash report).
     */
    @JvmStatic
    fun recent(): List<String> = synchronized(recent) { recent.toList() }

    // --- writing -------------------------------------------------------------------------

    private val start = System.nanoTime()
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** Writes one line now, whatever the levels (for [Logger] and for reports the user asked for). */
    @JvmStatic
    fun write(area: String, level: Int, mark: String, message: String, error: Throwable?) {
        // A problem said again and again (every frame): once, then a count every so often —
        // not thousands of lines and notices.
        if (level <= PROBLEMS && mark != "F") {
            val count = repeats(area, message, error) ?: return
            if (count > 1) return write(area, level, mark, "$message (and ${count - 1} times more in the last ${REPEAT_SECONDS} s)", error)
        }
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        val line = buildString {
            append(LocalTime.now().format(clock))
            append(String.format(java.util.Locale.ROOT, " %9.3f ", seconds))
            append(mark).append(' ')
            append(area.padEnd(8)).append(' ')
            append(message)
            if (error != null) append(" — ").append(error)
        }
        val shown = level <= levelOf(area)
        if (watchers.isNotEmpty()) {
            val entry = Entry(area, level, message, error, mark)
            // (A watcher that fails is not logged: that line would come here again.)
            for (watcher in watchers) runCatching { watcher(entry) }
        }
        if (level <= EVENTS) {
            synchronized(recent) {
                if (recent.size == RECENT_LINES) recent.removeFirst()
                // (A problem with where it came from: what a report is read for.)
                recent.addLast(if (error != null && level <= PROBLEMS) line + stackBrief(error) else line)
            }
        }
        val inFile = level <= fileLevel
        if (!shown && !inFile) return
        synchronized(this) {
            if (toConsole && shown) {
                System.err.println(line)
                if (error != null && level <= PROBLEMS) error.printStackTrace(System.err)
            }
            fileStream?.let { out ->
                out.println(line)
                fileBytes += line.length + 1
                if (error != null) {
                    val stack = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
                    out.print(stack)
                    fileBytes += stack.length
                }
                val limit = fileLimitBytes
                if (limit in 1..fileBytes) rotateFile()
            }
        }
        if (!shown) return
        listener?.let { runCatching { it(line) } }
    }

    // The first steps of [error]'s stack, and of its cause's, as more lines of one entry.
    private fun stackBrief(error: Throwable): String = buildString {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 3) {
            if (depth > 0) append("\n    caused by ").append(e)
            for (frame in e.stackTrace.take(STACK_BRIEF_FRAMES)) append("\n    at ").append(frame)
            e = e.cause.takeIf { it !== e }
            depth++
        }
    }

    private const val STACK_BRIEF_FRAMES = 8

    // Problems said lately, by their gist: when last let through, and how often since.
    private class Repeat(var since: Long, var count: Int)

    private val lately = LinkedHashMap<String, Repeat>()
    private val GIST_NOISE = Regex("\"[^\"]*\"|\\([^)]*\\)|\\d+")
    private val DIGITS = Regex("\\d+")
    private const val REPEAT_SECONDS = 10
    private const val REPEAT_BURST = 5

    // Null: the same problem was said less than REPEAT_SECONDS ago (left out, counted); else how
    // many times it came since it was last let through (1: the first).
    private fun repeats(area: String, message: String, error: Throwable?): Int? = synchronized(lately) {
        // (Its gist: without what changes from one time to the next, such as a window's title.)
        val gist = area + "|" + message.replace(GIST_NOISE, "") + "|" + error?.javaClass?.name + error?.message?.replace(DIGITS, "")
        val now = System.nanoTime()
        val seen = lately[gist]
        if (seen == null) {
            if (lately.size > 200) lately.remove(lately.keys.first())
            lately[gist] = Repeat(now, 1)
            return 1
        }
        if (now - seen.since < REPEAT_SECONDS * 1_000_000_000L) {
            seen.count++
            // (A few in a row are let through: only a flood is held back.)
            return if (seen.count <= REPEAT_BURST) 1 else null
        }
        val count = (seen.count - REPEAT_BURST + 1).coerceAtLeast(1)
        seen.since = now
        seen.count = 1
        return count
    }

    init {
        System.getenv("CAULIS_LOG")?.takeIf { it.isNotBlank() }?.let(::configure)
        System.getenv("CAULIS_LOG_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { file = Path.of(path) }.onFailure { warnSetting("cannot write the log file $path: ${it.message}") }
        }
    }
}

/**
 * The log of one area ([Log.AREAS]), e.g. `private val log = Logger("focus")`:
 *
 * ```
 * log.error("Drawing failed", e)       // level 1
 * log.warn("No monitor yet")           // level 1
 * log.info { "Window $window opened" } // level 2 — the text is only made when shown
 * log.trace { "Key $code pressed" }    // level 3
 * ```
 */
class Logger(val area: String) {

    // The level, cached until the settings change (checking costs a field read).
    private var cachedVersion = -1L
    private var cachedLevel = Log.PROBLEMS

    /** The level this area logs at now. */
    val level: Int
        get() {
            val version = Log.version
            if (version != cachedVersion) {
                cachedLevel = Log.levelOf(area)
                cachedVersion = version
            }
            return cachedLevel
        }

    /** True if level 3 lines are written — check before making costly ones. */
    val tracing: Boolean get() = level >= Log.EVERYTHING

    /** Something went wrong (level 1). */
    @JvmOverloads
    fun error(message: String, error: Throwable? = null) = Log.write(area, Log.PROBLEMS, "E", message, error)

    /** Something is not as it should be, but things go on (level 1). */
    @JvmOverloads
    fun warn(message: String, error: Throwable? = null) = Log.write(area, Log.PROBLEMS, "W", message, error)

    /** Something worth knowing happened (level 2; also kept in the black box). */
    inline fun info(message: () -> String) = Log.write(area, Log.EVENTS, "I", message(), null)

    fun info(message: String) = Log.write(area, Log.EVENTS, "I", message, null)

    /** A small step, for following exactly what happens (level 3). */
    inline fun trace(message: () -> String) {
        if (tracing) Log.write(area, Log.EVERYTHING, "T", message(), null)
    }

    fun trace(message: String) {
        if (tracing) Log.write(area, Log.EVERYTHING, "T", message, null)
    }

    /** The compositor cannot go on (written even at level 0). */
    @JvmOverloads
    fun fatal(message: String, error: Throwable? = null) = Log.write(area, Log.QUIET, "F", message, error)
}
