package dev.caulis.wm

import dev.caulis.core.Desktop
import dev.caulis.core.Scratchpad
import dev.caulis.core.Window
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

    /** Keyboard layouts as for `setxkbmap`, e.g. "us,ru", and options, e.g. "grp:win_space_toggle". */
    var keyboardLayouts = "us"
    var keyboardOptions = ""

    /** The terminal [openTerminal] (and the default Super+Return) starts. */
    var terminal = "foot"

    /**
     * Use the usual keys (Super+Q, Super+1..9, Alt+Tab, ... see [defaultBindings]). Your own
     * [bind] calls win over them. Ctrl+Alt+Backspace (quit) and Ctrl+Alt+F1..F12 (text
     * consoles) are always there: you must never be locked in.
     */
    var useDefaultBindings = true

    /** Name of the window in nested mode (and what the outer session knows it as). */
    var title = "Caulis"
    var appId = "dev.caulis.Nested"

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
    fun mode(mode: Mode): Caulis = apply { this.mode = mode }
    fun keyboard(layouts: String, options: String = ""): Caulis = apply {
        keyboardLayouts = layouts
        keyboardOptions = options
    }
    fun bind(combination: String, action: Consumer<Caulis>): Caulis = apply { bind(combination) { action.accept(this) } }
    fun onStart(action: Consumer<Caulis>): Caulis = apply { onStart { action.accept(this) } }
    fun floatWhen(rule: java.util.function.Predicate<Window>): Caulis = apply { floatWhen { rule.test(it) } }
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

    /** Starts [command] (run by /bin/sh) as a program inside the compositor. */
    fun launch(command: String) {
        checkNotNull(running) { "Caulis is not running yet" }.launch(command)
    }

    fun openTerminal() = launch(terminal)

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

    companion object {
        /** A new compositor with default settings (for Java; Kotlin can use [caulis]). */
        @JvmStatic
        fun create() = Caulis()

        /**
         * The usual keys, active unless [useDefaultBindings] is false:
         * Super+Return terminal, Super+Q close, Super+Up maximize, Super+F fullscreen,
         * Super+Down minimize, Super+V float/tile, Alt+Tab next window, Super+Tab overview
         * (the whole workspace zoomed out; click a window to go there), Super+Left/Right
         * focus neighbour, Super+1..9 workspace, Super+Shift+1..9 move window there.
         * For layouts that remember things: Super+Shift+arrows move the window,
         * Super+Ctrl+arrows resize it, Super+Alt+arrows preselect where the next window
         * goes (trees) / move the view (canvas) / move into the next column (columns),
         * Super+R rotate, Super+B balance, Super+W next column width, Super+S / Super+Shift+S
         * next window side by side / below (manual tiling).
         */
        /** The usual swipes: four fingers left / right = next / previous workspace. */
        val defaultSwipes: List<Triple<Int, Direction, Caulis.() -> Unit>> = listOf(
            Triple(4, Direction.LEFT) { desktop.switchTo(desktop.activeWorkspace.number + 1) },
            Triple(4, Direction.RIGHT) { desktop.switchTo((desktop.activeWorkspace.number - 1).coerceAtLeast(1)) },
        )

        val defaultBindings: List<Pair<String, Caulis.() -> Unit>> = buildList {
            add("Super+Return" to { openTerminal() })
            add("Super+Q" to { closeFocused() })
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
        exitProcess(1)
    }
}

/** The compositor cannot start because something it needs is missing (explained on stderr). */
class CannotStartException(message: String) : RuntimeException(message)
