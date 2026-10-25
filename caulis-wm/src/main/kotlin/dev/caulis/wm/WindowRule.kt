package dev.caulis.wm

import dev.caulis.core.Border
import dev.caulis.core.Blur
import dev.caulis.core.Desktop
import dev.caulis.core.Effects
import dev.caulis.core.Shadow
import dev.caulis.core.TitleBars
import dev.caulis.core.Window
import dev.caulis.core.WindowBehavior
import dev.caulis.core.WindowPlacement
import dev.caulis.core.WindowRequest
import dev.caulis.render.Color
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
 * rule { appId("mpv"); titleBars = TitleBars.NONE; opacity = 1f; margin(-8) }   // a video into the gaps
 * rule { appId("org.gnome.Calculator"); margin(40) }                         // with room around it
 * rule { isFloating(); cornerRadius = 16; shadow = true }                     // every floating window, while it floats
 * rule { appId("mpv"); keepAwake = true; ignore(WindowRequest.MAXIMIZE) }    // the screen stays on
 * rule { title("Picture-in-Picture"); pinned = true; takesFocus = false }    // on every workspace
 * rule { appId("org.keepassxc.KeePassXC"); hiddenFromCapture = true }        // black in screen sharing
 * rule { unless { isXwayland() }; tags("wayland") }                           // tags for other rules
 * rule { hasTag("wayland"); isFocused(); opacity = 0.95f }
 * ```
 *
 * Where and how a window starts ([workspace], [monitor], [floating], [size], [position],
 * [center], [maximized], [fullscreen], [focus]) is decided before its first frame; a window
 * that fits only later (programs like Spotify set their title after opening) gets it then,
 * once. Its looks ([titleBars], [effects], [opacity], [cornerRadius], [border], [shadow],
 * [blur]), its behaviour ([minSize], [keepAwake], [animated], [takesFocus], [ignore], ...),
 * its [tags] and its [margin] follow the rule as long as it fits — also a rule that asks for
 * a state ([isFloating], [isFocused], ...): it holds while the window is in it.
 * Later rules win over earlier ones where both say something.
 */
class WindowRule internal constructor() {

    // --- which windows ------------------------------------------------------------------

    private val conditions = ArrayList<Pair<String, (Window, Facts) -> Boolean>>()

    // What a rule may ask about a window besides the window itself.
    internal class Facts(
        val desktop: Desktop?,
        /** Its tags (given by hand, and by the rules before). */
        val tags: Set<String>,
        /** Its app id and title when it opened. */
        val initialAppId: String,
        val initialTitle: String,
    )

    private fun condition(name: String, test: (Window, Facts) -> Boolean) {
        conditions += name to test
    }

    private fun condition(name: String, test: (Window) -> Boolean) {
        conditions += name to { window, _ -> test(window) }
    }

    /** Windows whose app id is [name] (case does not matter): "firefox", "org.gnome.Nautilus", ... */
    fun appId(name: String) {
        condition("appId($name)") { it.appId.equals(name, ignoreCase = true) }
    }

    /** Windows whose app id fits [pattern] somewhere, e.g. `appId(Regex("^org\\.gnome\\."))`. */
    fun appId(pattern: Regex) {
        condition("appId(/$pattern/)") { pattern.containsMatchIn(it.appId) }
    }

    /** Windows whose title contains [text] (case does not matter). */
    fun title(text: String) {
        condition("title($text)") { it.title.contains(text, ignoreCase = true) }
    }

    /** Windows whose title fits [pattern] somewhere. */
    fun title(pattern: Regex) {
        condition("title(/$pattern/)") { pattern.containsMatchIn(it.title) }
    }

    /**
     * Windows of the program [name] — its executable, as `ps` shows it ("spotify", "steam",
     * "java"). Handy when the app id says little (Electron, games).
     */
    fun program(name: String) {
        condition("program($name)") { window -> programOf(window)?.equals(name, ignoreCase = true) == true }
    }

    /** Windows for which [condition] is true — anything a window can tell. */
    fun where(condition: (Window) -> Boolean) {
        condition("where(${condition.javaClass.name})", condition)
    }

    /** Windows whose app id was [name] when they opened (it may have changed since). */
    fun initialAppId(name: String) = condition("initialAppId($name)") { _, facts -> facts.initialAppId.equals(name, ignoreCase = true) }

    /** Windows whose title contained [text] when they opened. */
    fun initialTitle(text: String) = condition("initialTitle($text)") { _, facts -> facts.initialTitle.contains(text, ignoreCase = true) }

    /** Windows with the tag [name] (given by an earlier rule's [tags], or by the action `tag`). */
    fun hasTag(name: String) = condition("hasTag($name)") { _, facts -> name in facts.tags }

    /** While it floats ([value] false: while it is tiled). */
    @JvmOverloads
    fun isFloating(value: Boolean = true) = condition("isFloating($value)") { window, facts -> (facts.desktop?.isFloating(window) ?: !window.tiled) == value }

    /** While it is fullscreen (or not). */
    @JvmOverloads
    fun isFullscreen(value: Boolean = true) = condition("isFullscreen($value)") { window -> window.fullscreen == value }

    /** While it is maximized (or not). */
    @JvmOverloads
    fun isMaximized(value: Boolean = true) = condition("isMaximized($value)") { window -> window.maximized == value }

    /** While it has the keyboard (or not). */
    @JvmOverloads
    fun isFocused(value: Boolean = true) = condition("isFocused($value)") { window -> window.activated == value }

    /** While it is pinned (on every workspace) — or not. */
    @JvmOverloads
    fun isPinned(value: Boolean = true) = condition("isPinned($value)") { window, facts -> (facts.desktop?.isPinned(window) ?: false) == value }

    /** X11 programs (through Xwayland) — or, with false, Wayland ones. */
    @JvmOverloads
    fun isXwayland(value: Boolean = true) = condition("isXwayland($value)") { window -> (window !is dev.caulis.core.XdgWindow) == value }

    /** While it is on workspace [number]. */
    fun onWorkspace(number: Int) = condition("onWorkspace($number)") { window, facts -> facts.desktop?.workspaceNumberOf(window) == number }

    /** Windows that do NOT fit what is said inside: `unless { appId("firefox") }`. */
    fun unless(inside: WindowRule.() -> Unit) {
        val other = WindowRule().apply(inside)
        condition("unless(${other.conditionNames()})") { window, facts -> !other.fits(window, facts) }
    }

    /** Windows that fit at least one of the conditions inside: `anyOf { appId("mpv"); appId("vlc") }`. */
    fun anyOf(inside: WindowRule.() -> Unit) {
        val other = WindowRule().apply(inside)
        condition("anyOf(${other.conditionNames()})") { window, facts -> other.conditions.any { (_, test) -> test(window, facts) } }
    }

    /** Windows that fit all the conditions inside (for one choice of [anyOf]: `anyOf { allOf { appId("a"); isFloating() }; appId("b") }`). */
    fun allOf(inside: WindowRule.() -> Unit) {
        val other = WindowRule().apply(inside)
        condition("allOf(${other.conditionNames()})") { window, facts -> other.fits(window, facts) }
    }

    private fun conditionNames() = conditions.joinToString(", ") { it.first }

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

    /** True: it opens pinned — floating, on whichever workspace its monitor shows (the action `pin` toggles it). */
    var pinned: Boolean? = null

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

    internal var margins: dev.caulis.core.Margins? = null

    /**
     * Extra space around the window inside its place in the layout, the same on every side
     * (desktop units, -500 to 500). Negative: it reaches into the gaps (never past its monitor).
     */
    fun margin(all: Int) {
        margins = dev.caulis.core.Margins.all(all)
    }

    /** Extra space above and below ([vertical]) and left and right ([horizontal]). */
    fun margin(vertical: Int, horizontal: Int) {
        margins = dev.caulis.core.Margins(vertical, horizontal, vertical, horizontal)
    }

    /** Extra space on each side on its own. */
    fun margin(top: Int, right: Int, bottom: Int, left: Int) {
        margins = dev.caulis.core.Margins(top, right, bottom, left)
    }

    /** Who draws its title bar: [TitleBars.PROGRAM], [TitleBars.COMPOSITOR] or [TitleBars.NONE]. */
    var titleBars: TitleBars? = null

    /** How see-through it is, 0 to 1, focused or not (e.g. 1f for videos when others are see-through). */
    var opacity: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "opacity is 0 to 1, not $value" }
            field = value
        }

    /** The opacity of the window while others have the keyboard (else [opacity]). */
    var inactiveOpacity: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "opacity is 0 to 1, not $value" }
            field = value
        }

    /** How round its corners are, in pixels (0: square). */
    var cornerRadius: Int? = null
        set(value) {
            require(value == null || value in 0..200) { "a corner radius is 0 to 200, not $value" }
            field = value
        }

    /** False: no border; true: the usual one (or the one of [effects]). */
    var border: Boolean? = null

    /** How wide its border is, in pixels (it gets one). */
    var borderWidth: Int? = null
        set(value) {
            require(value == null || value in 1..50) { "a border is 1 to 50 pixels wide, not $value" }
            field = value
        }

    private var borderColors: Pair<Color, Color>? = null

    /** Its border's colours, with and without the keyboard (it gets a border). */
    @JvmOverloads
    fun borderColor(focused: Color, unfocused: Color = focused) {
        borderColors = focused to unfocused
    }

    /** False: no shadow; true: the usual one. */
    var shadow: Boolean? = null

    /** False: nothing blurred behind it; true: frosted glass behind its see-through parts. */
    var blur: Boolean? = null

    /** How much darker it is without the keyboard (0: never dimmed, e.g. a video beside your work). */
    var dimInactive: Float? = null
        set(value) {
            require(value == null || value in 0f..1f) { "dimInactive is 0 to 1, not $value" }
            field = value
        }

    internal var effectsChange: ((Effects) -> Effects)? = null

    /** Changes its effects: `effects { it.copy(blur = null, cornerRadius = 0) }`. */
    fun effects(change: (Effects) -> Effects) {
        effectsChange = change
    }

    // --- how they behave, as long as they fit ---------------------------------------------

    private var minSize: Pair<Int, Int>? = null
    private var maxSize: Pair<Int, Int>? = null

    /** Never smaller than this while floating (desktop units). */
    fun minSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a size is at least 1 x 1, not $width x $height" }
        minSize = width to height
    }

    /** Never bigger than this while floating (desktop units). */
    fun maxSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a size is at least 1 x 1, not $width x $height" }
        maxSize = width to height
    }

    /** True: while it is on screen, the screen stays on (a video player that does not ask itself). */
    var keepAwake: Boolean? = null

    /** False: it opens, closes and moves without animations. */
    var animated: Boolean? = null

    /** True: screenshots and screen sharing show a black box in its place. */
    var hiddenFromCapture: Boolean? = null

    /** False: it never gets the keyboard (picture-in-picture, an on-screen keyboard). */
    var takesFocus: Boolean? = null

    private val ignoredRequests = HashSet<WindowRequest>()

    /**
     * What its program asks that is not done: WindowRequest.FULLSCREEN, MAXIMIZE, MINIMIZE,
     * ACTIVATE (bringing itself forward: it is marked urgent instead).
     */
    fun ignore(vararg requests: WindowRequest) {
        ignoredRequests += requests
    }

    internal val tags = LinkedHashSet<String>()

    /** Gives it these tags while it fits, for later rules ([hasTag]); the action `tag` gives and takes them by hand. */
    fun tags(vararg names: String) {
        for (name in names) require(name.isNotBlank() && name.none { it.isWhitespace() }) { "a tag is one word, not \"$name\"" }
        tags += names
    }

    // --- used by Caulis ------------------------------------------------------------------

    internal fun fits(window: Window, facts: Facts = Facts(null, emptySet(), window.appId, window.title)): Boolean =
        conditions.all { (_, condition) -> condition(window, facts) }

    internal val hasPlacement: Boolean
        get() = workspace != null || monitor != null || floating != null || maximized != null || fullscreen != null ||
            width != null || x != null || centered || goToWorkspace || !focus || pinned != null

    internal fun placement() = WindowPlacement(
        workspace = workspace, monitor = monitor, floating = floating, width = width, height = height, x = x, y = y,
        center = centered, maximized = maximized, fullscreen = fullscreen, goToWorkspace = goToWorkspace, focus = focus,
        pinned = pinned,
    )

    internal val hasLooks: Boolean
        get() = effectsChange != null || opacity != null || inactiveOpacity != null || cornerRadius != null || border != null ||
            borderWidth != null || borderColors != null || shadow != null || blur != null || dimInactive != null

    internal fun effectsOf(fx: Effects): Effects {
        var changed = effectsChange?.invoke(fx) ?: fx
        cornerRadius?.let { changed = changed.copy(cornerRadius = it) }
        when (border) {
            false -> changed = changed.copy(border = null)
            true -> if (changed.border == null) changed = changed.copy(border = Border())
            null -> {}
        }
        if (border != false && (borderWidth != null || borderColors != null)) {
            var frame = changed.border ?: Border()
            borderWidth?.let { frame = frame.copy(width = it) }
            borderColors?.let { (on, off) -> frame = frame.copy(focused = dev.caulis.render.Paint.solid(on), unfocused = dev.caulis.render.Paint.solid(off)) }
            changed = changed.copy(border = frame)
        }
        when (shadow) {
            false -> changed = changed.copy(shadow = null, inactiveShadow = null)
            true -> if (changed.shadow == null) changed = changed.copy(shadow = Shadow())
            null -> {}
        }
        when (blur) {
            false -> changed = changed.copy(blur = null)
            true -> if (changed.blur == null) changed = changed.copy(blur = Blur())
            null -> {}
        }
        dimInactive?.let { changed = changed.copy(dimInactive = it) }
        opacity?.let { changed = changed.copy(opacity = it, inactiveOpacity = inactiveOpacity ?: it) }
        if (opacity == null) inactiveOpacity?.let { changed = changed.copy(inactiveOpacity = it) }
        return changed
    }

    internal val hasBehavior: Boolean
        get() = minSize != null || maxSize != null || keepAwake != null || animated != null || hiddenFromCapture != null ||
            takesFocus != null || ignoredRequests.isNotEmpty()

    internal fun behaviorOf(behavior: WindowBehavior): WindowBehavior = behavior.copy(
        minWidth = minSize?.first ?: behavior.minWidth,
        minHeight = minSize?.second ?: behavior.minHeight,
        maxWidth = maxSize?.first ?: behavior.maxWidth,
        maxHeight = maxSize?.second ?: behavior.maxHeight,
        keepsAwake = keepAwake ?: behavior.keepsAwake,
        animated = animated ?: behavior.animated,
        hiddenFromCapture = hiddenFromCapture ?: behavior.hiddenFromCapture,
        takesFocus = takesFocus ?: behavior.takesFocus,
        ignored = behavior.ignored + ignoredRequests,
    )

    /**
     * What the rule says, as text: the same after a reload if the rule did not change (so a
     * reload does not apply it to open windows again).
     */
    internal val signature: String
        get() = conditions.joinToString(",") { it.first } + " → " + placement()

    override fun toString() = "rule(${conditionNames().ifEmpty { "every window" }})"

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
