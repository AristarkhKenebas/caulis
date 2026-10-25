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
 * Messages of a level that is off cost nothing: their text is not even made.
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
        "config" to "your own code (key bindings, rules, events): its mistakes",
        "native" to "C libraries Caulis uses, the event loop and its watchdog",
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
                    if (name !in AREAS) warnSetting("there is no log area \"$name\" (the areas: ${AREAS.keys.joinToString()})")
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

    /** Also write the log to this file (appended), or null for none. `CAULIS_LOG_FILE` sets it too. */
    @JvmStatic
    var file: Path? = null
        @Synchronized set(value) {
            fileStream?.close()
            fileStream = value?.let {
                it.parent?.let(Files::createDirectories)
                PrintStream(Files.newOutputStream(it, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true)
            }
            field = value
        }

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
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        val line = buildString {
            append(LocalTime.now().format(clock))
            append(String.format(" %9.3f ", seconds))
            append(mark).append(' ')
            append(area.padEnd(8)).append(' ')
            append(message)
            if (error != null) append(" — ").append(error)
        }
        val shown = level <= levelOf(area)
        if (watchers.isNotEmpty()) {
            val entry = Entry(area, level, message, error, mark)
            for (watcher in watchers) runCatching { watcher(entry) }
        }
        if (level <= EVENTS) {
            synchronized(recent) {
                if (recent.size == RECENT_LINES) recent.removeFirst()
                recent.addLast(line)
            }
        }
        if (!shown) return
        synchronized(this) {
            if (toConsole) {
                System.err.println(line)
                if (error != null && level <= PROBLEMS) error.printStackTrace(System.err)
            }
            fileStream?.let { out ->
                out.println(line)
                if (error != null) error.printStackTrace(out)
            }
        }
        listener?.let { runCatching { it(line) } }
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
