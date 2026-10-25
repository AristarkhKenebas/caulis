package dev.caulis.wm

import dev.caulis.core.Effects
import dev.caulis.core.TitleBars
import dev.caulis.core.Window
import dev.caulis.core.WindowPlacement
import java.nio.file.Files
import java.nio.file.Path

/**
 * A rule for some windows, written in `rule { }`: which windows ([appId], [title],
 * [program], [where]) and what happens to them.
 *
 * ```
 * rule {
 *     appId("spotify")          // which windows (all that are given must fit)
 *     workspace = 9             // where it opens
 *     floating = true
 *     size(900, 600)
 *     center()
 * }
 * rule { title(Regex("Picture.in.Picture")); floating = true; size(480, 270); position(20, 20) }
 * rule { appId("mpv"); titleBars = TitleBars.NONE; opacity = 1f }
 * ```
 *
 * Where and how a window starts ([workspace], [monitor], [floating], [size], [position],
 * [center], [maximized], [fullscreen], [focus]) is decided before its first frame; a window
 * that fits only later (programs like Spotify set their title after opening) gets it then,
 * once. Its looks ([titleBars], [effects], [opacity]) follow the rule as long as it fits.
 * Later rules win over earlier ones where both say something.
 */
class WindowRule internal constructor() {

    // --- which windows ------------------------------------------------------------------

    private val conditions = ArrayList<Pair<String, (Window) -> Boolean>>()

    /** Windows whose app id is [name] (case does not matter): "firefox", "org.gnome.Nautilus", ... */
    fun appId(name: String) {
        conditions += "appId($name)" to { it.appId.equals(name, ignoreCase = true) }
    }

    /** Windows whose app id fits [pattern] somewhere, e.g. `appId(Regex("^org\\.gnome\\."))`. */
    fun appId(pattern: Regex) {
        conditions += "appId(/$pattern/)" to { pattern.containsMatchIn(it.appId) }
    }

    /** Windows whose title contains [text] (case does not matter). */
    fun title(text: String) {
        conditions += "title($text)" to { it.title.contains(text, ignoreCase = true) }
    }

    /** Windows whose title fits [pattern] somewhere. */
    fun title(pattern: Regex) {
        conditions += "title(/$pattern/)" to { pattern.containsMatchIn(it.title) }
    }

    /**
     * Windows of the program [name] — its executable, as `ps` shows it ("spotify", "steam",
     * "java"). Handy when the app id says little (Electron, games).
     */
    fun program(name: String) {
        conditions += "program($name)" to { window -> programOf(window)?.equals(name, ignoreCase = true) == true }
    }

    /** Windows for which [condition] is true — anything a window can tell. */
    fun where(condition: (Window) -> Boolean) {
        conditions += "where(${condition.javaClass.name})" to condition
    }

    // --- where and how they start --------------------------------------------------------

    /** The workspace it opens on (you stay where you are, unless [goToWorkspace]). */
    var workspace: Int? = null
        set(value) {
            require(value == null || value >= 1) { "workspace numbers start at 1, not $value" }
            field = value
        }

    /** The monitor it opens on: "DP-2", "HDMI-A-1", "eDP-1" (the log lists them). */
    var monitor: String? = null

    /** Show its workspace when it opens on another one. */
    var goToWorkspace = false

    /** True: it floats above the tiled windows; false: it is tiled (even a dialog). */
    var floating: Boolean? = null

    var maximized: Boolean? = null
    var fullscreen: Boolean? = null

    /** False: it opens without taking the keyboard (e.g. a chat that pops up). */
    var focus = true

    private var width: Int? = null
    private var height: Int? = null
    private var x: Int? = null
    private var y: Int? = null
    private var centered = false

    /** Its size when floating, in desktop units. */
    fun size(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a window is at least 1 x 1, not $width x $height" }
        this.width = width
        this.height = height
    }

    /** Its place when floating: from the top left of the monitor's free area (inside panels). */
    fun position(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    /** In the middle of its monitor when floating. */
    fun center() {
        centered = true
    }

    // --- how they look, as long as they fit ---------------------------------------------

    /** Who draws its title bar: [TitleBars.PROGRAM], [TitleBars.COMPOSITOR] or [TitleBars.NONE]. */
    var titleBars: TitleBars? = null

    /** How see-through it is, 0 to 1, focused or not (e.g. 1f for videos when others are see-through). */
    var opacity: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "opacity is 0 to 1, not $value" }
            field = value
        }

    internal var effectsChange: ((Effects) -> Effects)? = null

    /** Changes its effects: `effects { it.copy(blur = null, cornerRadius = 0) }`. */
    fun effects(change: (Effects) -> Effects) {
        effectsChange = change
    }

    // --- used by Caulis ------------------------------------------------------------------

    internal fun fits(window: Window): Boolean = conditions.all { (_, condition) -> condition(window) }

    internal val hasPlacement: Boolean
        get() = workspace != null || monitor != null || floating != null || maximized != null || fullscreen != null ||
            width != null || x != null || centered || goToWorkspace || !focus

    internal fun placement() = WindowPlacement(
        workspace = workspace, monitor = monitor, floating = floating, width = width, height = height, x = x, y = y,
        center = centered, maximized = maximized, fullscreen = fullscreen, goToWorkspace = goToWorkspace, focus = focus,
    )

    internal fun effectsOf(fx: Effects): Effects {
        val changed = effectsChange?.invoke(fx) ?: fx
        return opacity?.let { changed.copy(opacity = it, inactiveOpacity = it) } ?: changed
    }

    /**
     * What the rule says, as text: the same after a reload if the rule did not change (so a
     * reload does not apply it to open windows again).
     */
    internal val signature: String
        get() = conditions.joinToString(",") { it.first } + " → " + placement()

    override fun toString() = "rule(${conditions.joinToString(", ") { it.first }.ifEmpty { "every window" }})"

    private companion object {
        // By process (asked often: effects are decided every frame).
        private val programs = java.util.concurrent.ConcurrentHashMap<Int, String>()

        fun programOf(window: Window): String? {
            val pid = window.pid.takeIf { it > 0 } ?: return null
            return programs.getOrPut(pid) { readProgram(pid) ?: return null }
        }

        private fun readProgram(pid: Int): String? {
            // The executable's name (comm is cut at 15 letters: "gnome-text-edit").
            return runCatching { Files.readSymbolicLink(Path.of("/proc/$pid/exe")).fileName.toString() }.getOrNull()
                ?: runCatching { Files.readString(Path.of("/proc/$pid/comm")).trim() }.getOrNull()
        }
    }
}
