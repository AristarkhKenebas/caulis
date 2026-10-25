package dev.caulis.wm

import dev.caulis.event.EventLoop
import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/** How a laptop's own screen and a monitor or projector plugged into it are used together. */
enum class DisplayMode(val title: String) {
    /** Both, side by side: one desktop across them. */
    EXTEND("Extend"),

    /** The other one shows what the laptop's screen shows (a projector). */
    MIRROR("Mirror"),

    /** The laptop's screen alone; the other stays dark. */
    BUILT_IN_ONLY("Laptop screen only"),

    /** The other one alone; the laptop's screen stays dark. */
    EXTERNAL_ONLY("Other screen only");

    companion object {
        /** By its name in actions and settings: "extend", "mirror", "laptop", "external". */
        fun of(name: String): DisplayMode? = when (name.lowercase()) {
            "extend" -> EXTEND
            "mirror" -> MIRROR
            "laptop", "built-in", "builtin" -> BUILT_IN_ONLY
            "external", "other" -> EXTERNAL_ONLY
            else -> null
        }
    }
}

/**
 * The display modes of a laptop ([DisplayMode]): the laptop's key for them (F7 on many)
 * shows the choice, each press moves to the next one, and it is used a moment after the
 * last press. What was chosen is kept for each set of monitors: plugging the same projector in
 * again brings its mode back; a monitor never seen before is used beside the laptop's
 * screen, and the choice is shown so it can be changed at once. With the lid closed the
 * laptop's screen is off while another one is there.
 *
 * Only on the screen (the monitors are the backend's: [screens]).
 */
internal class Displays(
    private val running: Running,
    private val loop: EventLoop,
    private val screens: Screens,
    // Where the chosen modes are kept (null: the state folder, see below).
    stateFile: Path? = null,
) {

    /** What the backend has: the monitors plugged in and turning them on or off. */
    interface Screens {
        fun plugged(): List<Screen>
        fun setEnabled(name: String, on: Boolean)

        /** True if [name] is used as a mirror now (not as a monitor of its own). */
        fun mirroring(name: String): Boolean
    }

    /** A monitor plugged in: its connector ("HDMI-A-1"), who it is (EDID), and whether it is on. */
    data class Screen(val name: String, val id: String, val on: Boolean)

    private val log = Logger("monitors")

    var mode = DisplayMode.EXTEND
        private set
    private var lidClosed = false
    private var plugged: List<Screen> = emptyList()

    // The chooser on screen: the mode marked, and when it is used.
    private var marked: Int? = null
    private var choosing: EventLoop.Timer? = null

    private val remembered: MutableMap<String, DisplayMode> by lazy { load() }

    /** True if both a laptop screen and another monitor are plugged in (the modes mean something). */
    val both: Boolean get() = plugged.any { builtIn(it.name) } && plugged.any { !builtIn(it.name) }

    /** Whether the monitor [name] is used in the mode there is now. */
    fun wants(name: String): Boolean {
        if (!both) return true
        return if (builtIn(name)) mode != DisplayMode.EXTERNAL_ONLY && !lidShut() else mode != DisplayMode.BUILT_IN_ONLY
    }

    /** Whether the monitor [name] shows the laptop's screen (instead of a part of the desktop of its own). */
    fun mirrors(name: String): Boolean = both && mode == DisplayMode.MIRROR && !builtIn(name) && !lidShut()

    /** Monitors were plugged in or out (each one plugged now): the mode for this set. */
    fun pluggedChanged(now: List<Screen>) {
        plugged = now
        if (!both) {
            // One kind alone: whatever there is, is used (a laptop's screen kept dark comes back).
            loop.post { apply() }
            return
        }
        val key = key(now)
        val known = remembered[key]
        mode = known ?: DisplayMode.EXTEND
        if (known == null) {
            remembered[key] = mode
            save()
        }
        log.info { "Monitors ${now.joinToString { it.name }}: ${mode.title}${if (known == null) " (new to us)" else " (as last time)"}" }
        // (The backend turns the new ones on right after this; the ones on already follow.)
        loop.post {
            apply()
            if (known == null && running.config.displayChooserOnPlug) open(mode.ordinal)
        }
    }

    /** The laptop's key: shows the choice, or moves to the next one. */
    fun next() {
        plugged = screens.plugged()
        if (!both) {
            running.desktop.showLevel(if (plugged.size > 1) "Several monitors: arrange them in the settings" else "No other screen is plugged in")
            return
        }
        open(((marked ?: mode.ordinal) + 1) % DisplayMode.entries.size)
    }

    /** Uses [wanted] now (and keeps it for this set of monitors). */
    fun use(wanted: DisplayMode) {
        plugged = screens.plugged()
        if (!both) return
        choosing?.cancel()
        choosing = null
        marked = null
        remembered[key(plugged)] = wanted
        save()
        if (wanted == mode) return
        log.info { "Display mode: ${mode.title} → ${wanted.title}" }
        mode = wanted
        apply()
        running.desktop.showLevel(wanted.title)
    }

    /** The lid was closed or opened. */
    fun lid(closed: Boolean) {
        if (closed == lidClosed) return
        lidClosed = closed
        plugged = screens.plugged()
        if (both && running.config.lidTurnsScreenOff) apply()
    }

    // Each monitor plugged in as the mode wants it: the ones to be used first (never no screen
    // at all in between), then the ones that change between mirror and monitor of their own,
    // then the ones to be dark.
    fun apply() {
        plugged = screens.plugged()
        fun used(screen: Screen) = wants(screen.name) && running.config.monitorSettings[screen.name]?.enabled != false
        for (screen in plugged) if (!screen.on && used(screen)) screens.setEnabled(screen.name, true)
        for (screen in plugged) {
            if (screen.on && used(screen) && screens.mirroring(screen.name) != mirrors(screen.name)) {
                screens.setEnabled(screen.name, false)
                screens.setEnabled(screen.name, true)
            }
        }
        for (screen in plugged) if (screen.on && !used(screen)) screens.setEnabled(screen.name, false)
        plugged = screens.plugged()
    }

    private fun lidShut() = lidClosed && running.config.lidTurnsScreenOff

    private fun open(index: Int) {
        marked = index
        running.desktop.showChoices("Screens", DisplayMode.entries.map { it.title }, index)
        choosing?.cancel()
        choosing = loop.after(CHOOSE_MILLIS) {
            choosing = null
            val chosen = marked ?: return@after
            marked = null
            use(DisplayMode.entries[chosen])
        }
    }

    fun close() {
        choosing?.cancel()
    }

    // --- kept between sessions: one line per set of monitors, "MODE<tab>who they are" ---

    private fun key(screens: List<Screen>) = screens.map { it.id }.sorted().joinToString(" + ")

    private val file: Path by lazy {
        if (stateFile != null) return@lazy stateFile
        val state = System.getenv("XDG_STATE_HOME")?.takeIf { it.startsWith("/") }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local", "state")
        state.resolve(running.config.configName).resolve("displays")
    }

    private fun load(): MutableMap<String, DisplayMode> {
        val known = LinkedHashMap<String, DisplayMode>()
        try {
            if (Files.isRegularFile(file)) {
                for (line in Files.readAllLines(file)) {
                    val (name, who) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
                    DisplayMode.entries.firstOrNull { it.name == name }?.let { known[who] = it }
                }
            }
        } catch (e: Exception) {
            log.warn("The display modes kept in $file could not be read: $e")
        }
        return known
    }

    private fun save() {
        try {
            Files.createDirectories(file.parent)
            Files.write(file, remembered.entries.toList().takeLast(KEPT).map { (who, mode) -> "${mode.name}\t$who" })
        } catch (e: Exception) {
            log.warn("The display mode could not be kept in $file: $e")
        }
    }

    companion object {
        private const val CHOOSE_MILLIS = 1300L
        private const val KEPT = 50

        /** A laptop's own screen, by its connector's name (eDP-1, LVDS-1, DSI-1). */
        fun builtIn(name: String): Boolean = name.startsWith("eDP") || name.startsWith("LVDS") || name.startsWith("DSI")
    }
}
