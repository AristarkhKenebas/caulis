package dev.caulis.wm

import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * What the start's settings share: times written as `2.seconds` or `400.milliseconds` in them
 * without importing anything.
 */
abstract class StartupSettings internal constructor() {
    @get:JvmSynthetic
    val Int.milliseconds: Duration get() = toDuration(DurationUnit.MILLISECONDS)

    @get:JvmSynthetic
    val Int.seconds: Duration get() = toDuration(DurationUnit.SECONDS)

    @get:JvmSynthetic
    val Double.seconds: Duration get() = toDuration(DurationUnit.SECONDS)

    @get:JvmSynthetic
    val Int.minutes: Duration get() = toDuration(DurationUnit.MINUTES)
}

/**
 * When a program of [Autostart] that ended is started again: never ([NEVER], the default),
 * when it crashed ([onCrash]), whenever it ended ([always]), or when your own rule says so
 * ([whenEnded]) — at most [maxTimes] times within [withinMillis] (then it is given up, and
 * the log says so).
 *
 * A crash is any end but a clean one (status 0) and being stopped from outside (Ctrl+C,
 * `kill`: statuses 130, 137 and 143) — a segfault (139) or an abort (134) count.
 */
class Restart private constructor(
    /** How many times at most it is started again within [withinMillis]. */
    val maxTimes: Int,
    /** The time the restarts are counted in, in milliseconds. */
    val withinMillis: Long,
    private val wanted: (status: Int) -> Boolean,
    private val name: String,
) {
    init {
        require(maxTimes >= 0) { "restart: maxTimes must not be negative ($maxTimes)" }
        require(withinMillis > 0) { "restart: within must be more than 0 ($withinMillis ms)" }
    }

    /** True if a program that ended with [status] is to be started again (the limit aside). */
    fun wants(status: Int): Boolean = maxTimes > 0 && wanted(status)

    override fun toString() = if (maxTimes == 0) "never" else "$name (at most $maxTimes times in ${withinMillis / 1000} s)"

    companion object {
        /** Never started again (the default). */
        @JvmField
        val NEVER = Restart(0, 1, { false }, "never")

        /** Statuses of a program stopped from outside (Ctrl+C, killed, terminated): no crash. */
        private val STOPPED = setOf(130, 137, 143)

        /** Started again when it crashed (see [Restart]), at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        @JvmOverloads
        fun onCrash(maxTimes: Int = 3, withinMillis: Long = 60_000): Restart =
            Restart(maxTimes, withinMillis, { status -> status != 0 && status !in STOPPED }, "on crash")

        /** [onCrash] with the time as a Duration: `onCrash(maxTimes = 3, within = 1.minutes)`. */
        @JvmSynthetic
        fun onCrash(maxTimes: Int = 3, within: Duration): Restart = onCrash(maxTimes, within.inWholeMilliseconds)

        /** Started again whenever it ended (also cleanly), at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        @JvmOverloads
        fun always(maxTimes: Int = 3, withinMillis: Long = 60_000): Restart = Restart(maxTimes, withinMillis, { true }, "always")

        /** [always] with the time as a Duration. */
        @JvmSynthetic
        fun always(maxTimes: Int = 3, within: Duration): Restart = always(maxTimes, within.inWholeMilliseconds)

        /** Started again when [wanted] says so for its exit status, at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        fun whenEnded(maxTimes: Int, withinMillis: Long, wanted: java.util.function.IntPredicate): Restart =
            Restart(maxTimes, withinMillis, { wanted.test(it) }, "your rule")
    }
}

/**
 * Programs started with the session — a bar, a tray applet, a notification daemon — one after
 * another ([stagger] apart, so the start does not stutter), once the desktop is shown
 * ([afterReveal]):
 *
 * ```
 * autostart {
 *     stagger = 400.milliseconds
 *     app("waybar")
 *     app("nm-applet", "--indicator") {
 *         delay = 2.seconds
 *         ifNotRunning = true
 *         restart = OnCrash(maxTimes = 3, within = 1.minutes)
 *         after("waybar")
 *     }
 *     script("~/.config/foo/start.sh")
 *     shell("sleep 1; notify-send hi")
 *     desktopEntries = true   // also the .desktop files in ~/.config/autostart
 * }
 * ```
 *
 * They start once per session, the way [Caulis.launch] starts programs (the session's
 * environment, Wayland first, no input; [Caulis.stopProgramsOnQuit] ends them with it). A
 * reload never starts them again: it starts only the entries it adds, and stops watching
 * (restarting) the ones it takes away — those keep running. An entry counts as the same
 * when it runs the same command: changing its name, folder, environment or after does not
 * start a second copy (the change applies when it next starts), and an entry taken away and
 * put back by a later reload takes over its program if that still runs. The log area "autostart" tells
 * what was started when; the action `autostart` (and [Caulis.autostartStatus]) lists them.
 */
class Autostart : StartupSettings() {

    /** The time between two programs starting, in milliseconds (0: all at once). */
    var staggerMillis = 400L
        set(value) {
            require(value in 0..60_000) { "autostart: stagger is 0 to 60 s, not $value ms" }
            field = value
        }

    /** [staggerMillis] as a Duration: `stagger = 400.milliseconds`. */
    @get:JvmSynthetic
    @set:JvmSynthetic
    var stagger: Duration
        get() = staggerMillis.toDuration(DurationUnit.MILLISECONDS)
        set(value) {
            staggerMillis = value.inWholeMilliseconds
        }

    /**
     * True (the default): the programs start once the desktop is shown (see [Reveal]; at once
     * when the reveal is off); false: right at the start, while it is still dark.
     */
    var afterReveal = true

    /**
     * Also start the programs of the XDG autostart folders (the `.desktop` files in
     * `~/.config/autostart`, then in `/etc/xdg/autostart`), as other desktops do: except those
     * that are Hidden, not for this desktop (OnlyShowIn, NotShowIn: [Caulis.desktopName]) or
     * whose TryExec is not installed.
     */
    var desktopEntries = false

    /** Where desktop entries are looked for (a file in an earlier folder wins); null: the XDG folders. */
    var desktopEntryFolders: List<Path>? = null

    /**
     * How long an entry waits for those it is to start after ([Entry.after]) before it starts
     * anyway, in milliseconds.
     */
    var afterTimeoutMillis = 10_000L
        set(value) {
            require(value in 0..600_000) { "autostart: afterTimeout is 0 to 600 s, not $value ms" }
            field = value
        }

    internal val list = ArrayList<Entry>()

    /** The entries, in the order they were added. */
    val entries: List<Entry> get() = list.toList()

    /** A program and its arguments (no shell): `app("nm-applet", "--indicator")`. */
    fun app(vararg command: String): Entry = add(Entry(Kind.APP, command.toList()))

    /** [app], configured: `app("nm-applet") { ifNotRunning = true }`. */
    @JvmSynthetic
    fun app(vararg command: String, configure: Entry.() -> Unit): Entry = app(*command).apply(configure)

    /** A program and its arguments as a list. */
    fun app(command: List<String>): Entry = add(Entry(Kind.APP, command))

    /** [app] as a list, configured (Java). */
    fun app(command: List<String>, configure: java.util.function.Consumer<Entry>): Entry = app(command).also { configure.accept(it) }

    /** An executable file (a script; `~` is your home) and its arguments: `script("~/.config/foo/start.sh")`. */
    fun script(path: String, vararg arguments: String): Entry = add(Entry(Kind.SCRIPT, listOf(path) + arguments))

    /** [script], configured. */
    @JvmSynthetic
    fun script(path: String, vararg arguments: String, configure: Entry.() -> Unit): Entry = script(path, *arguments).apply(configure)

    /** A piece of shell (run by `/bin/sh -c`): `shell("sleep 1; notify-send hi")`. */
    fun shell(snippet: String): Entry = add(Entry(Kind.SHELL, listOf(snippet)))

    /** [shell], configured. */
    @JvmSynthetic
    fun shell(snippet: String, configure: Entry.() -> Unit): Entry = shell(snippet).apply(configure)

    /** [shell], configured (Java). */
    fun shell(snippet: String, configure: java.util.function.Consumer<Entry>): Entry = shell(snippet).also { configure.accept(it) }

    /** Adds [entry] (made by another [Autostart] too). */
    fun add(entry: Entry): Entry = entry.also { list += it }

    /** Takes the entry [name] away (true if there was one). */
    fun remove(name: String): Boolean = list.removeAll { it.name == name }

    /** Takes every entry away. */
    fun clear() = list.clear()

    // Java's chained setters.
    fun staggerMillis(millis: Long): Autostart = apply { staggerMillis = millis }
    fun afterReveal(on: Boolean): Autostart = apply { afterReveal = on }
    fun desktopEntries(on: Boolean): Autostart = apply { desktopEntries = on }

    /** What an entry starts: a program, an executable file, or a piece of shell. */
    enum class Kind { APP, SCRIPT, SHELL }

    /** A program to start (see [Autostart]). */
    class Entry(val kind: Kind, command: List<String>) : StartupSettings() {
        init {
            require(command.isNotEmpty() && command[0].isNotBlank()) { "autostart: an entry needs a command" }
        }

        /** The program and its arguments (an app or a script), or the shell snippet (alone). */
        val command: List<String> = command.toList()

        /**
         * Its name: for [after], the status and [ifNotRunning] (the process looked for). By
         * default the program's own name ("nm-applet"; a shell snippet's first program).
         */
        var name: String = when (kind) {
            Kind.SHELL -> Launcher.programName(command[0]).substringBefore(';').ifEmpty { command[0] }
            else -> command[0].trimEnd('/').substringAfterLast('/')
        }

        /** Waits this long more before it starts, in milliseconds (the others do not wait for it). */
        var delayMillis = 0L
            set(value) {
                require(value in 0..3_600_000) { "autostart: delay is 0 to 1 hour, not $value ms" }
                field = value
            }

        /** [delayMillis] as a Duration: `delay = 2.seconds`. */
        @get:JvmSynthetic
        @set:JvmSynthetic
        var delay: Duration
            get() = delayMillis.toDuration(DurationUnit.MILLISECONDS)
            set(value) {
                delayMillis = value.inWholeMilliseconds
            }

        /** Not started when a program of its [name] runs already (of this user; looked for in /proc). */
        var ifNotRunning = false

        /** When it is started again after it ended (see [Restart]). */
        var restart: Restart = Restart.NEVER

        internal val afterNames = ArrayList<String>()

        /** The entries it starts after (their names). */
        val after: List<String> get() = afterNames.toList()

        /** Starts only once the entry [name] has started (or [Autostart.afterTimeoutMillis] passed). */
        fun after(name: String): Entry = apply { afterNames += name }

        /** More environment for it (on top of the session's). */
        val env: MutableMap<String, String> = LinkedHashMap()

        /** Sets one variable of its environment. */
        fun env(name: String, value: String): Entry = apply { env[name] = value }

        /** The folder it starts in (`~` is your home); null: the compositor's. */
        var dir: String? = null

        // (Helpers for restart = ... inside its braces.)
        /** Never started again. */
        @get:JvmSynthetic
        val Never: Restart get() = Restart.NEVER

        /** Started again when it crashed: `restart = OnCrash(maxTimes = 3, within = 1.minutes)`. */
        @JvmSynthetic
        @Suppress("FunctionName")
        fun OnCrash(maxTimes: Int = 3, within: Duration = 1.minutes): Restart = Restart.onCrash(maxTimes, within)

        /** Started again whenever it ended: `restart = Always(maxTimes = 5)`. */
        @JvmSynthetic
        @Suppress("FunctionName")
        fun Always(maxTimes: Int = 3, within: Duration = 1.minutes): Restart = Restart.always(maxTimes, within)

        // Java's chained setters.
        fun name(name: String): Entry = apply { this.name = name }
        fun delayMillis(millis: Long): Entry = apply { delayMillis = millis }
        fun ifNotRunning(on: Boolean): Entry = apply { ifNotRunning = on }
        fun restart(restart: Restart): Entry = apply { this.restart = restart }
        fun dir(dir: String?): Entry = apply { this.dir = dir }

        // The same entry in two configurations (a reload): what it starts, and where.
        internal val key: String get() = listOf(kind, command, name, dir, env, afterNames).toString()

        // The same program in two configurations (a reload): what it runs, whatever else changed.
        internal val identity: String get() = listOf(kind, command).toString()

        override fun toString() = when (kind) {
            Kind.SHELL -> "shell \"${command[0]}\""
            else -> command.joinToString(" ")
        }

        internal fun copy(): Entry = Entry(kind, command).also { c ->
            c.name = name
            c.delayMillis = delayMillis
            c.ifNotRunning = ifNotRunning
            c.restart = restart
            c.afterNames += afterNames
            c.env.putAll(env)
            c.dir = dir
        }
    }

    // A copy (of a configuration).
    internal fun copy(): Autostart = Autostart().also { copyInto(it) }

    // Its settings and entries into [target] (whose own are replaced).
    internal fun copyInto(target: Autostart) {
        target.staggerMillis = staggerMillis
        target.afterReveal = afterReveal
        target.desktopEntries = desktopEntries
        target.desktopEntryFolders = desktopEntryFolders
        target.afterTimeoutMillis = afterTimeoutMillis
        target.list.clear()
        list.mapTo(target.list) { it.copy() }
    }
}

/**
 * Starts the entries of [Autostart] once per session and watches them: one after another
 * (the stagger apart), each after its delay and the entries it waits for, again after a
 * crash if it should be. Everything runs on the compositor's thread ([post], [later]);
 * [start] starts a program the session's way (null: it could not be started).
 */
internal class AutostartRunner(
    private val start: (command: List<String>, directory: java.io.File?, env: Map<String, String>) -> Process?,
    private val later: (millis: Long, action: () -> Unit) -> AutoCloseable,
    private val post: (() -> Unit) -> Unit,
    private val runningAlready: (name: String) -> Boolean = ::programRunning,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {

    private val log = Logger("autostart")

    private enum class State { WAITING, QUEUED, STARTED, ENDED, SKIPPED, FAILED, GAVE_UP, REMOVED }

    // An entry on its way: its state, process, restarts. [gapAfter]: the time to the next
    // start after it (null: the stagger).
    private inner class Tracked(var entry: Autostart.Entry, val fromSession: Boolean = false, val gapAfter: Long? = null) {
        var state = State.WAITING
        var process: Process? = null
        var startedAt = 0L
        var status: Int? = null
        // When it was started again (for the limit).
        val restarts = ArrayDeque<Long>()
        val timers = ArrayList<AutoCloseable>()
        // Waiting for these entries to start, then this runs.
        var waitingFor: Set<String> = emptySet()
        var whenDone: (() -> Unit)? = null
        // Started, or found running, or could not start: entries after it go on.
        var done = false

        fun cancelTimers() {
            timers.forEach(AutoCloseable::close)
            timers.clear()
        }

        fun describe(): String {
            val since = if (startedAt > 0) " ${(clock() - startedAt) / 1000} s ago" else ""
            val restarted = if (restarts.isNotEmpty()) ", started again ${restarts.size} times" else ""
            return "${entry.name}: " + when (state) {
                State.WAITING -> if (waitingFor.isNotEmpty()) "waiting for ${waitingFor.joinToString()}" else "waiting (delay ${entry.delayMillis} ms)"
                State.QUEUED -> "about to start"
                State.STARTED -> "running (pid ${process?.pid()}, started$since$restarted)"
                State.ENDED -> "ended (status $status$restarted)"
                State.SKIPPED -> "not started: it runs already"
                State.FAILED -> "could not be started"
                State.GAVE_UP -> "given up after ${restarts.size} restarts (status $status)"
                State.REMOVED -> "taken out of the configuration (left running, not watched)"
            } + " — ${entry}"
        }
    }

    private val tracked = ArrayList<Tracked>()
    private val queue = ArrayDeque<Tracked>()
    private var pumping = false
    private var stagger = 400L
    private var afterTimeout = 10_000L
    private var begun = false
    private var closed = false
    private var begunAt = 0L

    // Asked for before the start (startLater): started once the others begin.
    private val waitingToBegin = ArrayList<Tracked>()

    /** True once [begin] ran. */
    val started: Boolean get() = begun

    /** Starts the entries of [autostart] (and [extra] ones, e.g. desktop entries). Once only. */
    fun begin(autostart: Autostart, extra: List<Autostart.Entry> = emptyList()) {
        if (begun || closed) return
        begun = true
        begunAt = clock()
        takeSettings(autostart)
        val all = autostart.list.map { it.copy() } + extra
        log.info { if (all.isEmpty()) "Nothing to start" else "Starting ${all.size} programs, $stagger ms apart" }
        // (All known first: an entry may wait for one further down.)
        val now = all.map { Tracked(it) } + waitingToBegin
        waitingToBegin.clear()
        tracked += now
        now.forEach(::schedule)
    }

    /**
     * A reloaded configuration: its new entries start, those taken away are no longer
     * watched (they keep running). The others go on as they are (with their new restart
     * rule). An entry is the one from before when it runs the same command (kind and words):
     * a changed name, folder, environment or after does not start it again (that applies
     * when it is started next), and one taken away and put back takes its program over again
     * if that still runs.
     */
    fun reconfigure(autostart: Autostart, extra: List<Autostart.Entry> = emptyList()) {
        if (closed) return
        takeSettings(autostart)
        if (!begun) return
        val wanted = autostart.list.map { it.copy() } + extra
        // (Each one from before is the same as one entry at most: the same command twice is two programs.)
        val unmatched = tracked.filter { !it.fromSession }.toMutableList()
        val added = ArrayList<Autostart.Entry>()
        for (entry in wanted) {
            val same = unmatched.firstOrNull { it.entry.identity == entry.identity && it.state != State.REMOVED }
                ?: unmatched.firstOrNull { it.entry.identity == entry.identity && it.process?.isAlive == true }
            if (same == null) {
                added += entry
                continue
            }
            unmatched -= same
            if (same.entry.key != entry.key) {
                log.info { "${entry.name} changed in the configuration (its name, folder, environment or after): it is not started again; the change applies when it next starts" }
            }
            if (same.state == State.REMOVED) {
                same.state = State.STARTED
                log.info { "${entry.name} is back in the configuration: the one still running is watched again (not started twice)" }
            }
            same.entry = entry // (its restart rule, delay, ... as the configuration says now)
        }
        for (t in unmatched) {
            if (t.state == State.REMOVED) continue
            t.cancelTimers()
            queue.remove(t)
            t.whenDone = null
            log.info { "${t.entry.name} was taken out of the configuration: ${if (t.state == State.STARTED) "left running, no longer watched" else "not started"}" }
            t.state = State.REMOVED
        }
        if (added.isEmpty()) return
        log.info { "Starting what the reload added: ${added.joinToString { it.name }}" }
        val now = added.map { Tracked(it) }
        tracked += now
        now.forEach(::schedule)
    }

    private fun takeSettings(autostart: Autostart) {
        stagger = autostart.staggerMillis
        afterTimeout = autostart.afterTimeoutMillis
    }

    /**
     * Starts [command] (its words, no shell) in [directory] in the queue: after the others
     * waiting, [gapMillis] before the next (null: the stagger); before [begin], once the others
     * begin. For session restore.
     */
    fun startLater(command: List<String>, directory: java.io.File?, gapMillis: Long? = null) {
        require(command.isNotEmpty() && command[0].isNotBlank()) { "startLater: no command" }
        val entry = Autostart.Entry(Autostart.Kind.APP, command).also { it.dir = directory?.path }
        val t = Tracked(entry, fromSession = true, gapAfter = gapMillis?.coerceAtLeast(0))
        if (closed) return
        if (!begun) {
            waitingToBegin += t
            log.trace { "${entry.name} starts once the others do" }
        } else {
            tracked += t
            schedule(t)
        }
    }

    /** Each entry and how it is, one per line. */
    fun status(): List<String> = tracked.map { it.describe() } + waitingToBegin.map { "${it.entry.name}: waiting for the start — ${it.entry}" }

    override fun close() {
        closed = true
        tracked.forEach { it.cancelTimers() }
        queue.clear()
    }

    // [t] (known already) starts after its delay and the entries it waits for.
    private fun schedule(t: Tracked) {
        val unknown = t.entry.afterNames.filter { name -> tracked.none { it !== t && it.entry.name == name && it.state != State.REMOVED } }
        if (unknown.isNotEmpty()) log.warn("${t.entry.name} is to start after ${unknown.joinToString()}, but there is no such entry: not waited for")
        if (t.entry.delayMillis > 0) {
            log.trace { "${t.entry.name} waits ${t.entry.delayMillis} ms" }
            t.timers += later(t.entry.delayMillis) { waitForOthers(t) }
        } else {
            waitForOthers(t)
        }
    }

    // Starts [t] once the entries it waits for started (or the time for that is up).
    private fun waitForOthers(t: Tracked) {
        if (t.state == State.REMOVED || closed) return
        val missing = t.entry.afterNames.filter { name ->
            tracked.any { it !== t && it.entry.name == name && it.state != State.REMOVED } && tracked.none { it.entry.name == name && it.done }
        }.toSet()
        if (missing.isEmpty()) return enqueue(t)
        t.waitingFor = missing
        log.trace { "${t.entry.name} waits for ${missing.joinToString()}" }
        t.whenDone = { enqueue(t) }
        t.timers += later(afterTimeout) {
            if (t.whenDone == null) return@later
            log.warn("${t.entry.name} waited $afterTimeout ms for ${t.waitingFor.joinToString()} to start: starting it anyway")
            t.waitingFor = emptySet()
            t.whenDone = null
            enqueue(t)
        }
    }

    private fun enqueue(t: Tracked) {
        if (t.state == State.REMOVED || closed) return
        t.state = State.QUEUED
        queue.addLast(t)
        pump()
    }

    // One from the queue, then the next one the stagger (or its own gap) later.
    private fun pump() {
        if (pumping || closed) return
        val next = queue.removeFirstOrNull() ?: return
        launch(next)
        val gap = next.gapAfter ?: stagger
        if (gap <= 0) return pump()
        pumping = true
        later(gap) {
            pumping = false
            pump()
        }
    }

    private fun launch(t: Tracked) {
        val entry = t.entry
        if (entry.ifNotRunning && safely(false) { runningAlready(entry.name) }) {
            log.info { "${entry.name} runs already: not started" }
            t.state = State.SKIPPED
            return finished(t)
        }
        val command = commandOf(entry) ?: run {
            t.state = State.FAILED
            return finished(t)
        }
        val directory = entry.dir?.let { java.io.File(expandHome(it)) }?.takeIf { dir ->
            dir.isDirectory.also { if (!it) log.warn("${entry.name}: the folder ${entry.dir} is not there; started in the compositor's") }
        }
        val process = safely(null) { start(command, directory, entry.env) }
        if (process == null) {
            log.warn("${entry.name} could not be started")
            t.state = State.FAILED
            return finished(t)
        }
        t.process = process
        t.state = State.STARTED
        t.startedAt = clock()
        log.info { "Started ${entry.name} (pid ${process.pid()}, ${t.startedAt - begunAt} ms after the start)" }
        process.onExit().thenAccept { ended -> post { ended(t, ended) } }
        finished(t)
    }

    // [t] started (or will not): those waiting for it go on.
    private fun finished(t: Tracked) {
        if (t.done) return
        t.done = true
        for (other in tracked.toList()) {
            val then = other.whenDone ?: continue
            if (t.entry.name !in other.waitingFor) continue
            other.waitingFor = other.waitingFor - t.entry.name
            if (other.waitingFor.isEmpty()) {
                other.whenDone = null
                then()
            }
        }
    }

    private fun ended(t: Tracked, process: Process) {
        if (t.process !== process) return
        val status = process.exitValue()
        t.status = status
        if (t.state == State.REMOVED || closed) return log.trace { "${t.entry.name} ended (status $status)" }
        t.state = State.ENDED
        val restart = t.entry.restart
        if (!restart.wants(status)) return log.info { "${t.entry.name} ended (status $status)" }
        // Only the restarts within the time count.
        val now = clock()
        while (t.restarts.isNotEmpty() && now - t.restarts.first() > restart.withinMillis) t.restarts.removeFirst()
        if (t.restarts.size >= restart.maxTimes) {
            t.state = State.GAVE_UP
            log.warn("${t.entry.name} ended (status $status) ${restart.maxTimes + 1} times within ${restart.withinMillis / 1000} s: not started again")
            return
        }
        t.restarts.addLast(now)
        log.info { "${t.entry.name} ended (status $status): starting it again (${t.restarts.size} of ${restart.maxTimes})" }
        enqueue(t)
    }

    // The command an entry runs: the program's words, the script's, or sh -c.
    private fun commandOf(entry: Autostart.Entry): List<String>? = when (entry.kind) {
        Autostart.Kind.SHELL -> listOf("/bin/sh", "-c", entry.command[0])
        Autostart.Kind.APP -> listOf(expandHome(entry.command[0])) + entry.command.drop(1)
        Autostart.Kind.SCRIPT -> {
            val file = Path.of(expandHome(entry.command[0]))
            when {
                !Files.exists(file) -> null.also { log.warn("${entry.name}: the script $file is not there") }
                !Files.isExecutable(file) -> null.also { log.warn("${entry.name}: the script $file cannot be run (chmod +x it)") }
                else -> listOf(file.toString()) + entry.command.drop(1)
            }
        }
    }

    private fun <T> safely(fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        log.warn("Autostart: $e")
        fallback
    }

    companion object {
        /** `~` and `~/...` as the home folder. */
        fun expandHome(path: String): String {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return when {
                path == "~" -> home
                path.startsWith("~/") -> home + path.substring(1)
                else -> path
            }
        }

        /**
         * True if a process of this user named [name] runs (its /proc comm, or its program's
         * file name): what [Autostart.Entry.ifNotRunning] looks for.
         */
        fun programRunning(name: String): Boolean {
            val me = ProcessHandle.current().pid()
            val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int
            val comm = name.take(15) // (the kernel keeps 15 characters of it)
            return Files.list(Path.of("/proc")).use { dirs ->
                dirs.anyMatch { dir ->
                    val pid = dir.fileName.toString().toLongOrNull() ?: return@anyMatch false
                    if (pid == me) return@anyMatch false
                    try {
                        if (Files.getAttribute(dir, "unix:uid") as Int != uid) return@anyMatch false
                        if (Files.readString(dir.resolve("comm")).trim() == comm) return@anyMatch true
                        val argv0 = Files.readAllBytes(dir.resolve("cmdline")).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                        argv0.substringAfterLast('/') == name
                    } catch (_: Exception) {
                        false // (it ended meanwhile, or is not ours to read)
                    }
                }
            }
        }
    }
}
