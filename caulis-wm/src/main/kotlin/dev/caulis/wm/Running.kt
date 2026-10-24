package dev.caulis.wm

import dev.caulis.core.Compositor
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.FrameStats
import dev.caulis.core.LinuxDmabuf
import dev.caulis.core.Presentation
import dev.caulis.core.Shm
import dev.caulis.core.XdgShell
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.KeyBindings.Modifier
import dev.caulis.core.input.Keymap
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/**
 * A running compositor, the same in every mode: the Wayland socket, the features offered
 * to programs, the window manager, keys and starting programs.
 */
internal class Running(
    private val config: Caulis,
    val loop: EventLoop,
    val renderer: Renderer,
    width: Int,
    height: Int,
    /** Null where switching text consoles is impossible (nested mode). */
    switchConsole: ((Int) -> Unit)?,
) : AutoCloseable {

    private val log = System.getLogger("caulis")

    val display = Display(loop)
    private val keymap = Keymap.fromNames(layouts = config.keyboardLayouts, options = config.keyboardOptions)
    val seat = Seat(display, keymap)
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    val keys = KeyBindings()

    init {
        Shm(display)
        if (renderer.renderNode != null) LinuxDmabuf(display, renderer)
        DataDeviceManager(display, seat)
        Presentation(display)
        XdgShell(display).listener = desktop
        desktop.layout = config.layout
        socket = config.socketDirectory?.let(display::addSocket) ?: display.addSocket()

        // Defaults first, the user's own bindings after them (later ones win).
        if (config.useDefaultBindings) {
            Caulis.defaultBindings.forEach { (combination, action) -> keys.bind(combination) { safely(combination) { action(config) } } }
        }
        config.bindings.forEach { (combination, action) -> keys.bind(combination) { safely(combination) { action(config) } } }

        // The user's rules and events (dialogs and fixed-size windows always float).
        val defaultRule = desktop.floatRule
        desktop.floatRule = { window -> defaultRule(window) || config.floatRules.any { rule -> safely("floatWhen", false) { rule(window) } } }
        config.windowOpened.forEach { action -> desktop.onWindowOpened { window -> action(config, window) } }
        config.windowClosed.forEach { action -> desktop.onWindowClosed { window -> action(config, window) } }
        config.focusChanged.forEach { action -> desktop.onFocusChanged { window -> action(config, window) } }
        config.workspaceChanged.forEach { action -> desktop.onWorkspaceChanged { workspace -> action(config, workspace.number) } }
        // Never locked in: quitting and text consoles always work.
        keys.bind("Ctrl+Alt+BackSpace") { quit() }
        if (switchConsole != null) for (n in 1..12) keys.bind("Ctrl+Alt+F$n") { switchConsole(n) }
    }

    fun quit() = loop.stop()

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor. If the program
     * does not exist, that is said in plain words instead of nothing happening.
     */
    fun launch(command: String) {
        try {
            val builder = ProcessBuilder("/bin/sh", "-c", command)
            builder.environment().apply {
                put("WAYLAND_DISPLAY", socket)
                remove("DISPLAY") // no X11 server inside (yet)
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.start().onExit().thenAccept { process ->
                // 127 is the shell's "command not found".
                if (process.exitValue() == 127) {
                    log.log(System.Logger.Level.WARNING, "\"$command\" could not be started: the program was not found (is it installed?)")
                }
            }
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "\"$command\" could not be started: ${e.message}")
        }
    }

    // Runs the user's code; a mistake in it is reported and the compositor keeps running.
    private fun <T> safely(what: String, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        log.log(System.Logger.Level.ERROR, "Your code for \"$what\" failed (ignored): $e", e)
        fallback
    }

    private fun safely(what: String, action: () -> Unit) = safely(what, Unit, action)

    /** A key from the keyboard: the compositor's combination, or passed to the focused program. */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        if (!keys.key(evdevCode, pressed)) desktop.key(timeMillis, evdevCode, pressed)
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed && keys.isHeld(Modifier.SUPER) && (button == BTN_LEFT || button == BTN_RIGHT)) {
            val window = desktop.windowAt(desktop.pointerX, desktop.pointerY)
            if (window != null) {
                desktop.pointerButton(timeMillis, button, true, passToApps = false)
                if (button == BTN_LEFT) {
                    desktop.beginMove(window)
                } else {
                    desktop.beginResize(window, desktop.nearestEdges(window, desktop.pointerX, desktop.pointerY))
                }
                return
            }
        }
        desktop.pointerButton(timeMillis, button, pressed)
    }

    /** Wraps a backend's `present` so drawing is timed by [stats] (if measuring is on). */
    fun timed(stats: FrameStats?, present: ((GpuTarget, Int) -> Unit) -> Unit): ((GpuTarget, Int) -> Unit) -> Unit {
        if (stats == null) return present
        return { draw ->
            present { target, age ->
                stats.drawStarted()
                draw(target, age)
                stats.drawFinished()
            }
        }
    }

    /** Runs the user's start actions and the main loop until [quit]. */
    fun runLoop() {
        config.running = this
        try {
            config.exitAfterSeconds?.let { seconds -> loop.after(seconds * 1000) { quit() } }
            config.startActions.forEach { action -> safely("onStart") { action(config) } }
            loop.run()
        } finally {
            config.running = null
        }
    }

    override fun close() {
        seat.close()
        keymap.close()
        display.close()
    }

    private companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
    }
}

/** Milliseconds on the clock Wayland and libinput timestamps use. */
internal fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
