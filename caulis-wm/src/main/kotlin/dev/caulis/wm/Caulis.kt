package dev.caulis.wm

import dev.caulis.core.Desktop
import dev.caulis.core.Window
import dev.caulis.core.layout.Floating
import dev.caulis.core.layout.Layout
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
 *     .layout(new Dwindle())
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

    internal val bindings = ArrayList<Pair<String, Caulis.() -> Unit>>()
    internal val startActions = ArrayList<Caulis.() -> Unit>()

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

    /** Starts the compositor and returns when it is closed. Throws if it cannot start. */
    fun run() {
        val nested = when (mode) {
            Mode.AUTO -> !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()
            Mode.NESTED -> true
            Mode.SCREEN -> false
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
         * Super+Down minimize, Super+V float/tile, Alt+Tab next window, Super+Left/Right
         * focus neighbour, Super+1..9 workspace, Super+Shift+1..9 move window there.
         */
        val defaultBindings: List<Pair<String, Caulis.() -> Unit>> = buildList {
            add("Super+Return" to { openTerminal() })
            add("Super+Q" to { closeFocused() })
            add("Super+Up" to { focusedWindow?.let { desktop.setMaximized(it, !it.maximized) } })
            add("Super+F" to { focusedWindow?.let { desktop.setFullscreen(it, !it.fullscreen) } })
            add("Super+Down" to { focusedWindow?.let(desktop::minimize) })
            add("Super+V" to { focusedWindow?.let { desktop.setFloating(it, !desktop.isFloating(it)) } })
            add("Alt+Tab" to { desktop.cycleFocus() })
            add("Super+Left" to { desktop.focusDirection(-1, 0) })
            add("Super+Right" to { desktop.focusDirection(1, 0) })
            for (n in 1..9) {
                add("Super+$n" to { desktop.switchTo(n) })
                add("Super+Shift+$n" to { focusedWindow?.let { desktop.moveToWorkspace(it, n) } })
            }
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
    } catch (e: Exception) {
        System.err.println("The compositor stopped: ${e.message}")
        e.cause?.let { System.err.println("  (${it.message})") }
        System.err.println("\nDetails for a bug report:")
        e.printStackTrace()
        exitProcess(1)
    }
}
