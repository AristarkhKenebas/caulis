package dev.caulis.wm

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
