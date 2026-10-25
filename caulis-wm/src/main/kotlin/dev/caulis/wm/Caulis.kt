package dev.caulis.wm

import dev.caulis.core.Desktop
import dev.caulis.core.Effects
import dev.caulis.core.LayerSurface
import dev.caulis.core.Scratchpad
import dev.caulis.core.TitleBarStyle
import dev.caulis.core.TitleBars
import dev.caulis.core.Window
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.log.Log
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Direction
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingCommand
import java.util.function.Consumer
import kotlin.system.exitProcess

/**
 * A complete compositor, configured in a few lines and started with [run]:
 *
 * ```
 * fun main() = caulis {
 *     layout = Dwindle(Gaps(inner = 8, outer = 12))
 *     bind("Super+Return") { launch("foot") }
 *     bind("Super+Q") { closeFocused() }
 * }
 * ```
 *
 * The same from Java:
 *
 * ```
 * Caulis.create()
 *     .layout(Layouts.Dwindle())
 *     .bind("Super+Return", c -> c.launch("foot"))
 *     .run();
 * ```
 *
 * It runs as a window inside the current Wayland session when there is one, and directly
 * on the screen when started from a text console ([mode]).
 */
class Caulis {

    // --- settings (before run) ------------------------------------------------------------

    enum class Mode {
        /** Nested when WAYLAND_DISPLAY is set, otherwise on the screen. */
        AUTO,

        /** A window inside the current Wayland session. */
        NESTED,

        /** Directly on the screen (from a text console). */
        SCREEN,
    }

    var mode = Mode.AUTO

    /** How windows are arranged on every workspace; can also be changed while running. */
    var layout: Layout = Floating
        set(value) {
            field = value
            running?.desktop?.layout = value
        }

    /**
     * How long windows take to glide to new places, minimize into the dock, zoom into the
     * overview, ... in milliseconds (0 = no animations, they jump).
     */
    var animationMillis = 220L
        set(value) {
            require(value >= 0) { "animationMillis must not be negative" }
            field = value
            running?.desktop?.animationMillis = value
        }

    /**
     * How windows look: rounded corners, a border, shadows, blur, transparency. Off by
     * default; e.g. `effects = Effects.MODERN` or
     * `effects = Effects(cornerRadius = 10, shadow = Shadow(), blur = Blur(), inactiveOpacity = 0.9f)`.
     * Can also be changed while running.
     */
    var effects: Effects = Effects.NONE
        set(value) {
            field = value
            running?.desktop?.effects = value
        }

    /**
     * How big everything is: 1.0, 1.25, 1.5, 2.0 ... (also fractions). Programs are told and
     * draw at exactly this size, sharp — X11 programs too. Can be changed while running.
     */
    var scale: Double = 1.0
        set(value) {
            require(value in 0.5..4.0) { "scale must be between 0.5 and 4" }
            field = value
            running?.applyScale()
        }

    /**
     * Who draws windows' title bars: [TitleBars.PROGRAM] (each program its own, the default),
     * [TitleBars.COMPOSITOR] (the same for all, see [titleBarStyle]) or [TitleBars.NONE].
     * Programs that do not take part (GTK 4 apps) always draw their own — never two bars.
     */
    var titleBars: TitleBars = TitleBars.PROGRAM
        set(value) {
            field = value
            running?.desktop?.titleBars = value
        }

    /** How the compositor's title bars look: height, font, colours, buttons, or a painter of your own. */
    var titleBarStyle: TitleBarStyle = TitleBarStyle()
        set(value) {
            field = value
            running?.desktop?.titleBarStyle = value
        }

    internal val titleBarRules = ArrayList<(Window) -> TitleBars?>()

    /**
     * Title bars for some programs: `titleBarsFor { if (it.appId == "spotify") TitleBars.COMPOSITOR else null }`
     * (null = [titleBars]). The first rule with an answer wins.
     */
    @JvmSynthetic
    fun titleBarsFor(rule: (Window) -> TitleBars?) {
        titleBarRules += rule
    }

    /**
     * Run X11 programs too (through Xwayland, started only when the first one connects). Off
     * = Wayland programs only.
     */
    var xwayland = true

    /**
     * Start programs the Wayland way when they can (GTK, Qt, Electron, Firefox are told to
     * prefer it), and through X11 when that fails: a program that quits at once without a
     * window is started again through Xwayland. Off = programs choose for themselves.
     */
    var waylandFirst = true

    /**
     * Java programs (Swing, AWT) on a fractional scale such as 1.5: they can only draw at whole
     * scales, so they get an X server of their own, draw at the next whole scale (2) and are
     * shown at exactly yours — sharp and the right size. Off = they draw at the nearest whole
     * scale with the other X11 programs (sharp, but up to a third too big or too small).
     */
    var sharpJavaScaling = true

    /** Programs always started through X11, by name: `x11Programs += "steam"`. */
    val x11Programs: MutableSet<String> = LinkedHashSet()

    /** Keyboard layouts as for `setxkbmap`, e.g. "us,ru", and options, e.g. "grp:win_space_toggle". */
    var keyboardLayouts = "us"
    var keyboardOptions = ""

    /**
     * The screen locker [lock] (and the default Super+L) starts, e.g. "swaylock -c 1e1e2e",
     * "gtklock" or "hyprlock". While it runs, nothing of the desktop is visible and only it
     * gets the keyboard; it unlocks when you type your password.
     */
    var lockCommand = "swaylock"

    /**
     * The cursor theme, e.g. "Caulis-Black", "Bibata-Modern-Ice", "Adwaita". Null = the
     * default: Bibata if the project uses `caulis-cursor-bibata` (the template does), else
     * `XCURSOR_THEME` if set, else Caulis's own sharp white cursors ("Caulis").
     * [CursorTheme.available] lists what is there.
     */
    var cursorTheme: String? = null
        set(value) {
            field = value
            running?.applyCursor()
        }

    /** The cursor size in pixels (24 is usual; 32 or 48 for a bigger one). Null = `XCURSOR_SIZE` or 24. */
    var cursorSize: Int? = null
        set(value) {
            require(value == null || value in 8..512) { "cursorSize must be between 8 and 512" }
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor while typing; it comes back when the mouse moves. */
    var hideCursorWhileTyping = false
        set(value) {
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor after the mouse was still for this many seconds (0 = never). */
    var hideCursorAfterSeconds = 0
        set(value) {
            require(value >= 0) { "hideCursorAfterSeconds must not be negative" }
            field = value
            running?.applyCursor()
        }

    /** The terminal [openTerminal] (and the default Super+Return) starts. */
    var terminal = "foot"

    /**
     * Use the usual keys (Super+Q, Super+1..9, Alt+Tab, ... see [defaultBindings]). Your own
     * [bind] calls win over them. Ctrl+Alt+Backspace (quit) and Ctrl+Alt+F1..F12 (text
     * consoles) are always there: you must never be locked in.
     */
    var useDefaultBindings = true

    /**
     * What programs started inside are told the desktop is called (`XDG_CURRENT_DESKTOP`).
     * Desktop portals use it to choose who handles screen sharing (see the guide).
     */
    var desktopName = "caulis"

    /** Name of the window in nested mode (and what the outer session knows it as). */
    var title = "Caulis"
    var appId = "dev.caulis.Nested"

    /**
     * How much the log says: 0 nearly nothing, 1 problems (the default), 2 what happens
     * (windows, monitors, layouts, ...), 3 everything step by step. `CAULIS_LOG` (e.g.
     * `CAULIS_LOG=3`) wins over this, so you can look closer without changing code.
     * See [Log] and [log] for single areas.
     */
    var logLevel: Int = Log.PROBLEMS
        set(value) {
            require(value in 0..3) { "logLevel must be 0, 1, 2 or 3" }
            field = value
        }

    /** Also write the log to this file (e.g. Path.of("/tmp/my-compositor.log")). `CAULIS_LOG_FILE` wins over it. */
    var logFile: java.nio.file.Path? = null

    internal val logAreas = LinkedHashMap<String, Int>()

    /**
     * One area of the log in more (or less) detail than [logLevel], e.g. `log("focus", 3)`
     * to follow every focus change. The areas: [Log.AREAS] ("wayland", "windows", "focus",
     * "input", "layout", "render", "drm", ...).
     */
    fun log(area: String, level: Int) {
        require(level in 0..3) { "a log level is 0, 1, 2 or 3" }
        require(area in Log.AREAS) { "there is no log area \"$area\"; the areas: ${Log.AREAS.keys.joinToString()}" }
        logAreas[area] = level
    }

    /** Writes everything the window manager knows now (windows, focus, panels, ...) to the log. Super+F12 by default. */
    fun dumpState() {
        Log.write("state", Log.QUIET, "D", "The state now:\n" + desktop.describe().trimEnd(), null)
    }

    /** Print frame timing every few seconds. */
    var stats = false

    /** Show fullscreen programs' pictures directly on the monitor (screen mode). */
    var directScanout = true

    /** Quit by itself after this many seconds (handy for trying things out safely). */
    var exitAfterSeconds: Long? = null

    /** Advanced: the session socket to open the nested window in (null = WAYLAND_DISPLAY). */
    var nestedIn: java.nio.file.Path? = null

    /** Advanced: where programs' socket is created (null = XDG_RUNTIME_DIR, the usual place). */
    var socketDirectory: java.nio.file.Path? = null

    internal val bindings = ArrayList<Pair<String, Caulis.() -> Unit>>()
    internal val startActions = ArrayList<Caulis.() -> Unit>()
    internal val floatRules = ArrayList<(Window) -> Boolean>()
    internal val effectRules = ArrayList<(Window, Effects) -> Effects>()
    internal val panelEffectRules = ArrayList<(LayerSurface) -> Effects?>()
    internal val scratchpads = ArrayList<ScratchpadSetup>()
    internal val swipes = ArrayList<Triple<Int, Direction, Caulis.() -> Unit>>()

    /**
     * Swiping on the touchpad with this many fingers drags the view (a canvas, a strip of
     * columns) right under them; when they lift, it settles on the nearest whole place.
     * 0 turns it off (the swipe then goes to the program under the pointer).
     */
    var dragViewFingers = 3

    /**
     * Pinching with this many fingers opens the overview, following the fingers (spreading
     * them closes it again). 0 turns it off.
     */
    var overviewPinchFingers = 4

    internal class ScratchpadSetup(val name: String, val key: String?, val command: String?, val style: Scratchpad.Style, val match: (Window) -> Boolean)
    internal val windowOpened = ArrayList<Caulis.(Window) -> Unit>()
    internal val windowClosed = ArrayList<Caulis.(Window) -> Unit>()
    internal val focusChanged = ArrayList<Caulis.(Window?) -> Unit>()
    internal val workspaceChanged = ArrayList<Caulis.(Int) -> Unit>()

    /** Runs [action] when the key [combination] (e.g. "Super+Shift+Q") is pressed. */
    @JvmSynthetic // Java uses the Consumer version below
    fun bind(combination: String, action: Caulis.() -> Unit) {
        bindings += combination to action
    }

    /** Runs [action] once everything is ready (e.g. to start a panel or wallpaper program). */
    @JvmSynthetic
    fun onStart(action: Caulis.() -> Unit) {
        startActions += action
    }

    /**
     * Windows for which [rule] is true float above the tiled ones instead of joining the
     * layout, e.g. `floatWhen { it.appId == "pavucontrol" }`. Dialogs always float.
     */
    @JvmSynthetic
    fun floatWhen(rule: (Window) -> Boolean) {
        floatRules += rule
    }

    /**
     * Changes the [effects] of some windows: [rule] gets a window and the effects it would
     * have, and returns the ones it gets, e.g. no blur and full opacity for videos:
     * `effectsFor { window, fx -> if (window.appId == "mpv") fx.copy(blur = null, inactiveOpacity = 1f) else fx }`.
     */
    @JvmSynthetic
    fun effectsFor(rule: (Window, Effects) -> Effects) {
        effectRules += rule
    }

    /**
     * Effects for panels, docks and bars (none by default), by what they call themselves:
     * `panelEffects { if (it.namespace == "waybar") Effects(blur = Blur()) else null }`.
     * Null = no effects.
     */
    @JvmSynthetic
    fun panelEffects(rule: (LayerSurface) -> Effects?) {
        panelEffectRules += rule
    }

    /**
     * A special workspace, shown and hidden with [key] above whatever workspace is on screen:
     *
     * ```
     * scratchpad("terminal", key = "Super+Grave", command = "foot --app-id terminal")
     * scratchpad("music", key = "Super+M", command = "spotify", style = Scratchpad.Edge(Side.RIGHT)) { it.appId == "spotify" }
     * scratchpad("notes", key = "Super+N", command = "gnome-text-editor", style = Scratchpad.Overlay())
     * ```
     *
     * The first press starts [command] when none of its windows is open; the window slides
     * in as soon as it appears. Windows belong to it when [match] says so — by default
     * when their app id is [name]. Styles: [Scratchpad.Edge] (slides in from an edge, the
     * default), [Scratchpad.Center] (floating in the middle), [Scratchpad.Overlay] (a whole
     * workspace above the current one).
     */
    @JvmSynthetic
    fun scratchpad(
        name: String,
        key: String? = null,
        command: String? = null,
        style: Scratchpad.Style = Scratchpad.Edge(),
        match: (Window) -> Boolean = { it.appId == name },
    ) {
        scratchpads += ScratchpadSetup(name, key, command, style, match)
    }

    /**
     * Runs [action] when the touchpad is swiped with [fingers] fingers towards [direction]
     * (when the fingers lift). By default, four fingers left/right go to the next/previous
     * workspace.
     */
    @JvmSynthetic
    fun swipe(fingers: Int, direction: Direction, action: Caulis.() -> Unit) {
        require(fingers in 3..5) { "Touchpads report gestures with 3 to 5 fingers, not $fingers" }
        swipes += Triple(fingers, direction, action)
    }

    /** Shows or hides the scratchpad [name] (starting its program if needed). */
    fun toggleScratchpad(name: String) {
        checkNotNull(running) { "Caulis is not running yet" }.toggleScratchpad(name)
    }

    /** Runs [action] when a window appears, e.g. to move it to a workspace. */
    @JvmSynthetic
    fun onWindowOpened(action: Caulis.(Window) -> Unit) {
        windowOpened += action
    }

    /** Runs [action] when a window goes away. */
    @JvmSynthetic
    fun onWindowClosed(action: Caulis.(Window) -> Unit) {
        windowClosed += action
    }

    /** Runs [action] when another window gets the keyboard (null = none). */
    @JvmSynthetic
    fun onFocusChanged(action: Caulis.(Window?) -> Unit) {
        focusChanged += action
    }

    /** Runs [action] with the number of the workspace that is now shown. */
    @JvmSynthetic
    fun onWorkspaceChanged(action: Caulis.(Int) -> Unit) {
        workspaceChanged += action
    }

    // --- the same for Java (each returns this, so calls can be chained) -----------------

    fun layout(layout: Layout): Caulis = apply { this.layout = layout }
    fun terminal(command: String): Caulis = apply { terminal = command }
    fun lockCommand(command: String): Caulis = apply { lockCommand = command }
    fun scale(scale: Double): Caulis = apply { this.scale = scale }
    fun waylandFirst(on: Boolean): Caulis = apply { waylandFirst = on }
    fun x11Program(name: String): Caulis = apply { x11Programs += name }
    fun sharpJavaScaling(on: Boolean): Caulis = apply { sharpJavaScaling = on }
    fun titleBars(titleBars: TitleBars): Caulis = apply { this.titleBars = titleBars }
    fun titleBarsFor(rule: java.util.function.Function<Window, TitleBars?>): Caulis = apply { titleBarsFor { rule.apply(it) } }
    fun logLevel(level: Int): Caulis = apply { logLevel = level }
    fun logArea(area: String, level: Int): Caulis = apply { log(area, level) }
    fun cursor(theme: String?, size: Int?): Caulis = apply {
        cursorTheme = theme
        cursorSize = size
    }
    fun mode(mode: Mode): Caulis = apply { this.mode = mode }
    fun keyboard(layouts: String, options: String = ""): Caulis = apply {
        keyboardLayouts = layouts
        keyboardOptions = options
    }
    fun bind(combination: String, action: Consumer<Caulis>): Caulis = apply { bind(combination) { action.accept(this) } }
    fun onStart(action: Consumer<Caulis>): Caulis = apply { onStart { action.accept(this) } }
    fun floatWhen(rule: java.util.function.Predicate<Window>): Caulis = apply { floatWhen { rule.test(it) } }
    fun effects(effects: Effects): Caulis = apply { this.effects = effects }
    fun effectsFor(rule: java.util.function.BiFunction<Window, Effects, Effects>): Caulis = apply { effectsFor { window, fx -> rule.apply(window, fx) } }
    fun panelEffects(rule: java.util.function.Function<LayerSurface, Effects?>): Caulis = apply { panelEffects { rule.apply(it) } }
    fun scratchpad(name: String, key: String?, command: String?, style: Scratchpad.Style): Caulis =
        apply { scratchpad(name, key, command, style) { it.appId == name } }
    fun onWindowOpened(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowOpened { action.accept(this, it) } }
    fun onWindowClosed(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowClosed { action.accept(this, it) } }

    // --- while running ------------------------------------------------------------------

    internal var running: Running? = null

    /** The window manager (workspaces, focus, layouts). Only while running. */
    val desktop: Desktop get() = checkNotNull(running) { "Caulis is not running yet" }.desktop

    /** The window with the keyboard focus, if any. */
    val focusedWindow: Window? get() = running?.desktop?.focusedWindow

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor — the Wayland way
     * first ([waylandFirst]), or through X11 with [x11] = true.
     */
    @JvmOverloads
    fun launch(command: String, x11: Boolean = false) {
        checkNotNull(running) { "Caulis is not running yet" }.launch(command, x11)
    }

    fun openTerminal() = launch(terminal)

    /** Locks the screen with [lockCommand]. */
    fun lock() = launch(lockCommand)

    fun closeFocused() = desktop.closeFocused()

    /** Stops the compositor; [run] returns. */
    fun quit() {
        running?.quit()
    }

    /**
     * Starts the compositor and returns when it is closed. First checks that everything
     * it needs is there; throws [CannotStartException] with plain explanations if not.
     */
    fun run() {
        applyLogSettings()
        val nested = when (mode) {
            Mode.AUTO -> !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()
            Mode.NESTED -> true
            Mode.SCREEN -> false
        }
        if (!Preflight.report(Preflight.check(this, nested), title)) {
            throw CannotStartException("$title cannot start (see the problems listed above)")
        }
        if (nested) runNested(this) else runOnScreen(this)
    }

    // The log settings from code, then CAULIS_LOG / CAULIS_LOG_FILE on top (they win).
    private fun applyLogSettings() {
        Log.level = logLevel
        for ((area, level) in logAreas) Log.setLevel(area, level)
        System.getenv("CAULIS_LOG")?.takeIf { it.isNotBlank() }?.let(Log::configure)
        if (System.getenv("CAULIS_LOG_FILE").isNullOrBlank()) logFile?.let { Log.file = it }
        Log.write("log", Log.EVENTS, "I", "Log level ${Log.level}" + Log.AREAS.keys.filter { Log.levelOf(it) != Log.level }
            .joinToString("") { ", $it ${Log.levelOf(it)}" } + (Log.file?.let { ", also written to $it" } ?: ""), null)
    }

    companion object {
        /** A new compositor with default settings (for Java; Kotlin can use [caulis]). */
        @JvmStatic
        fun create() = Caulis()

        /** The usual swipes: four fingers left / right = next / previous workspace. */
        val defaultSwipes: List<Triple<Int, Direction, Caulis.() -> Unit>> = listOf(
            Triple(4, Direction.LEFT) { desktop.switchTo(desktop.activeWorkspace.number + 1) },
            Triple(4, Direction.RIGHT) { desktop.switchTo((desktop.activeWorkspace.number - 1).coerceAtLeast(1)) },
        )

        /**
         * The usual keys, active unless [useDefaultBindings] is false:
         * Super+Return terminal, Super+Q close, Super+L lock the screen, Super+F12 write the
         * state to the log, Super+Up maximize, Super+F fullscreen,
         * Super+Down minimize, Super+V float/tile, Alt+Tab next window, Super+Tab overview
         * (the whole workspace zoomed out; click a window to go there), Super+Left/Right
         * focus neighbour, Super+1..9 workspace, Super+Shift+1..9 move window there.
         * For layouts that remember things: Super+Shift+arrows move the window,
         * Super+Ctrl+arrows resize it, Super+Alt+arrows preselect where the next window
         * goes (trees) / move the view (canvas) / move into the next column (columns),
         * Super+R rotate, Super+B balance, Super+W next column width, Super+S / Super+Shift+S
         * next window side by side / below (manual tiling).
         */
        val defaultBindings: List<Pair<String, Caulis.() -> Unit>> = buildList {
            add("Super+Return" to { openTerminal() })
            add("Super+Q" to { closeFocused() })
            add("Super+F12" to { dumpState() })
            add("Super+L" to { lock() })
            add("Super+Up" to { focusedWindow?.let { desktop.setMaximized(it, !it.maximized) } })
            add("Super+F" to { focusedWindow?.let { desktop.setFullscreen(it, !it.fullscreen) } })
            add("Super+Down" to { focusedWindow?.let(desktop::minimize) })
            add("Super+V" to { focusedWindow?.let { desktop.setFloating(it, !desktop.isFloating(it)) } })
            add("Alt+Tab" to { desktop.cycleFocus() })
            add("Super+Tab" to { desktop.toggleOverview() })
            add("Super+Left" to { desktop.focusDirection(-1, 0) })
            add("Super+Right" to { desktop.focusDirection(1, 0) })
            for (n in 1..9) {
                add("Super+$n" to { desktop.switchTo(n) })
                add("Super+Shift+$n" to { focusedWindow?.let { desktop.moveToWorkspace(it, n) } })
            }
            for (direction in Direction.entries) {
                val key = direction.name.lowercase().replaceFirstChar(Char::uppercase) // "Left", ...
                add("Super+Shift+$key" to { desktop.tilingCommand(TilingCommand.Move(direction)) })
                add("Super+Ctrl+$key" to { desktop.tilingCommand(TilingCommand.Resize(direction)) })
                // One key, whatever fits the layout: preselect, pan the canvas, or take into a column.
                add("Super+Alt+$key" to {
                    desktop.tilingCommand(TilingCommand.Preselect(direction)) ||
                        desktop.tilingCommand(TilingCommand.Pan(direction)) ||
                        desktop.tilingCommand(TilingCommand.Consume(direction))
                })
            }
            add("Super+R" to { desktop.tilingCommand(TilingCommand.Rotate) })
            add("Super+B" to { desktop.tilingCommand(TilingCommand.Balance) })
            add("Super+W" to { desktop.tilingCommand(TilingCommand.CycleWidth) })
            add("Super+S" to { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = true)) })
            add("Super+Shift+S" to { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = false)) })
        }
    }
}

/**
 * Configures and runs a compositor (see [Caulis]). Problems that stop it from starting are
 * printed in plain words and end the program.
 */
fun caulis(configure: Caulis.() -> Unit) {
    val compositor = Caulis().apply(configure)
    try {
        compositor.run()
    } catch (e: CannotStartException) {
        exitProcess(1) // the reasons were already printed
    } catch (e: Exception) {
        System.err.println("The compositor stopped: ${e.message}")
        e.cause?.let { System.err.println("  (${it.message})") }
        System.err.println("\nDetails for a bug report:")
        e.printStackTrace()
        // What happened just before (kept even when the log level was low).
        val recent = Log.recent().takeLast(40)
        if (recent.isNotEmpty()) {
            System.err.println("\nThe last events:")
            recent.forEach(System.err::println)
        }
        runCatching { compositor.running?.desktop?.describe() }.getOrNull()?.let {
            System.err.println("\nThe state when it stopped:\n$it")
        }
        exitProcess(1)
    }
}

/** The compositor cannot start because something it needs is missing (explained on stderr). */
class CannotStartException(message: String) : RuntimeException(message)
