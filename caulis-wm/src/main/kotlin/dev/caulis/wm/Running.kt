package dev.caulis.wm

import dev.caulis.log.Logger
import dev.caulis.core.Compositor
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Effects
import dev.caulis.core.ForeignToplevels
import dev.caulis.core.ScreenCapture
import dev.caulis.core.FrameStats
import dev.caulis.core.LayerShell
import dev.caulis.core.LinuxDmabuf
import dev.caulis.core.Presentation
import dev.caulis.core.SessionLockManager
import dev.caulis.core.Shm
import dev.caulis.core.XdgShell
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.core.input.CursorShape
import dev.caulis.core.input.Gesture
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.KeyBindings.Modifier
import dev.caulis.core.input.Keymap
import dev.caulis.core.input.PointerGestures
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import dev.caulis.tiling.Direction

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

    private val log = Logger("config")
    private val clientsLog = Logger("clients")
    private val cursorLog = Logger("cursor")
    private val gesturesLog = Logger("gestures")

    val display = Display(loop)
    private val keymap = Keymap.fromNames(layouts = config.keyboardLayouts, options = config.keyboardOptions)
    val seat = Seat(display, keymap)
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)
    private val pointerGestures = PointerGestures(display, seat)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    val keys = KeyBindings()

    // The only keys of ours while the screen is locked; everything else goes to the locker.
    private val lockedKeys = KeyBindings()

    // Where a cursor theme that came with a library is put for programs that draw their own cursor.
    private var cursorFolder: java.nio.file.Path? = null

    init {
        Shm(display)
        if (renderer.renderNode != null) LinuxDmabuf(display, renderer)
        DataDeviceManager(display, seat)
        Presentation(display)
        XdgShell(display).listener = desktop
        LayerShell(display).listener = desktop
        SessionLockManager(display).listener = desktop
        CursorShape(display, seat)
        ScreenCapture(display, desktop, renderer, ForeignToplevels(display, desktop))
        applyCursor()
        desktop.layout = config.layout
        socket = config.socketDirectory?.let(display::addSocket) ?: display.addSocket()

        // Defaults first, the user's own bindings after them (later ones win).
        if (config.useDefaultBindings) {
            Caulis.defaultBindings.forEach { (combination, action) -> keys.bind(combination) { safely(combination) { action(config) } } }
        }
        config.bindings.forEach { (combination, action) -> keys.bind(combination) { safely(combination) { action(config) } } }

        // The user's rules and events (dialogs and fixed-size windows always float).
        val defaultRule = desktop.floatRule
        desktop.effects = config.effects
        desktop.animationMillis = config.animationMillis
        desktop.windowEffects = { window ->
            config.effectRules.fold(desktop.effects) { fx, rule -> safely("effectsFor", fx) { rule(window, fx) } }
        }
        desktop.layerEffects = { layer ->
            config.panelEffectRules.firstNotNullOfOrNull { rule -> safely("panelEffects", null) { rule(layer) } } ?: Effects.NONE
        }
        desktop.floatRule = { window -> defaultRule(window) || config.floatRules.any { rule -> safely("floatWhen", false) { rule(window) } } }
        for (pad in config.scratchpads) {
            desktop.addScratchpad(pad.name, pad.style) { window -> safely("scratchpad ${pad.name}", false) { pad.match(window) } }
            pad.key?.let { key -> keys.bind(key) { toggleScratchpad(pad.name) } }
        }
        config.windowOpened.forEach { action -> desktop.onWindowOpened { window -> action(config, window) } }
        config.windowClosed.forEach { action -> desktop.onWindowClosed { window -> action(config, window) } }
        config.focusChanged.forEach { action -> desktop.onFocusChanged { window -> action(config, window) } }
        config.workspaceChanged.forEach { action -> desktop.onWorkspaceChanged { workspace -> action(config, workspace.number) } }
        // Never locked in: quitting and text consoles always work (also while the screen is locked).
        for (bindings in listOf(keys, lockedKeys)) {
            bindings.bind("Ctrl+Alt+BackSpace") { quit() }
            if (switchConsole != null) for (n in 1..12) bindings.bind("Ctrl+Alt+F$n") { switchConsole(n) }
        }
    }

    fun quit() = loop.stop()


    /** Applies the cursor settings (theme, size, hiding). */
    fun applyCursor() {
        val theme = CursorTheme.load(config.cursorTheme, config.cursorSize)
        if (!theme.found) {
            cursorLog.warn("The cursor theme \"${theme.name}\" is not installed; using the system's. " +
                "Installed: ${CursorTheme.available().joinToString()}")
        }
        cursorLog.info { "Cursor theme ${theme.name}${if (theme.found) "" else " (not found)"}, ${theme.size} px" }
        desktop.cursorTheme = theme
        desktop.hideCursorWhileTyping = config.hideCursorWhileTyping
        desktop.hideCursorAfterMillis = config.hideCursorAfterSeconds * 1000L
        cursorFolder = safely("cursor theme files", null) {
            // On disk, once (a theme is tens of megabytes; the runtime folder is in memory).
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache")
            val folder = java.nio.file.Path.of(cache, "caulis", "icons")
            if (theme.exportTo(folder)) folder else null
        }
    }

    // When each scratchpad's program was started (so a second press does not start it twice).
    private val startedAt = HashMap<String, Long>()

    /** Shows or hides a scratchpad; starts its program if it has no window yet. */
    fun toggleScratchpad(name: String) {
        if (desktop.toggleScratchpad(name)) return
        val command = config.scratchpads.firstOrNull { it.name == name }?.command ?: return
        val now = System.nanoTime()
        val last = startedAt[name]
        if (last != null && now - last < STARTING_NANOS) return // still starting
        startedAt[name] = now
        launch(command)
    }

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor. If the program
     * does not exist, that is said in plain words instead of nothing happening.
     */
    fun launch(command: String) {
        clientsLog.info { "Starting: $command" }
        try {
            val builder = ProcessBuilder("/bin/sh", "-c", command)
            builder.environment().apply {
                put("WAYLAND_DISPLAY", socket)
                put("XDG_CURRENT_DESKTOP", config.desktopName)
                // Programs that draw their own cursor use the same theme and size.
                val theme = desktop.cursorTheme
                put("XCURSOR_THEME", theme.name)
                put("XCURSOR_SIZE", theme.size.toString())
                cursorFolder?.let { folder ->
                    put("XCURSOR_PATH", (listOf(folder) + CursorTheme.searchPath()).joinToString(":"))
                }
                remove("DISPLAY") // no X11 server inside (yet)
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.start().onExit().thenAccept { process ->
                // 127 is the shell's "command not found".
                if (process.exitValue() == 127) {
                    clientsLog.warn("\"$command\" could not be started: the program was not found (is it installed?)")
                }
            }
        } catch (e: Exception) {
            clientsLog.warn("\"$command\" could not be started: ${e.message}")
        }
    }

    // Runs the user's code; a mistake in it is reported and the compositor keeps running.
    private fun <T> safely(what: String, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        log.error("Your code for \"$what\" failed (ignored): $e", e)
        fallback
    }

    private fun safely(what: String, action: () -> Unit) = safely(what, Unit, action)

    /** A key from the keyboard: the compositor's combination, or passed to the focused program. */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        val (active, other) = if (desktop.locked) lockedKeys to keys else keys to lockedKeys
        // A key pressed before locking may be released after it (Super+L): both must see the release.
        if (!pressed) other.key(evdevCode, false)
        if (!active.key(evdevCode, pressed)) desktop.key(timeMillis, evdevCode, pressed)
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed && !desktop.locked && keys.isHeld(Modifier.SUPER) && (button == BTN_LEFT || button == BTN_RIGHT)) {
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

    // --- touchpad gestures --------------------------------------------------------------------

    // What the gesture going on does: drag the view, open the overview, a swipe action, or
    // nothing of ours (then it goes to the program under the pointer).
    private enum class GestureUse { NONE, DRAG, SWIPE, PINCH, PROGRAM }

    private var gestureUse = GestureUse.NONE
    private var gestureFingers = 0
    private var swipeX = 0.0
    private var swipeY = 0.0
    private var pinchStartedOpen = false
    private var pinchProgress = 0.0

    private val swipes get() = (if (config.useDefaultBindings) Caulis.defaultSwipes else emptyList()) + config.swipes

    /** A touchpad gesture: the compositor's own (see [Caulis.dragViewFingers] ...) or the program's. */
    fun gesture(gesture: Gesture) = safely("gesture") {
        gesturesLog.trace { "$gesture (fingers ${gestureFingers}, use $gestureUse)" }
        when (gesture) {
            is Gesture.SwipeBegin -> {
                gestureFingers = gesture.fingers
                swipeX = 0.0
                swipeY = 0.0
                gestureUse = when {
                    desktop.locked -> GestureUse.PROGRAM // the locker's
                    gesture.fingers == config.dragViewFingers -> GestureUse.DRAG
                    swipes.any { it.first == gesture.fingers } -> GestureUse.SWIPE
                    else -> GestureUse.PROGRAM
                }
            }
            is Gesture.SwipeUpdate -> when (gestureUse) {
                GestureUse.DRAG -> desktop.dragView(gesture.dx, gesture.dy)
                GestureUse.SWIPE -> {
                    swipeX += gesture.dx
                    swipeY += gesture.dy
                }
                else -> {}
            }
            is Gesture.SwipeEnd -> when (gestureUse) {
                GestureUse.DRAG -> desktop.endViewDrag()
                GestureUse.SWIPE -> if (!gesture.cancelled) swipeDirection()?.let { direction ->
                    swipes.filter { it.first == gestureFingers && it.second == direction }.forEach { (_, _, action) -> action(config) }
                }
                else -> {}
            }
            is Gesture.PinchBegin -> {
                gestureFingers = gesture.fingers
                gestureUse = if (gesture.fingers == config.overviewPinchFingers && !desktop.locked) GestureUse.PINCH else GestureUse.PROGRAM
                pinchStartedOpen = desktop.inOverview
                pinchProgress = if (pinchStartedOpen) 1.0 else 0.0
            }
            is Gesture.PinchUpdate -> if (gestureUse == GestureUse.PINCH) {
                // Fingers half as far apart = fully open; twice as far = fully closed again.
                pinchProgress = if (pinchStartedOpen) 1 - (gesture.scale - 1) / 1.0 else (1 - gesture.scale) / 0.5
                desktop.setOverviewProgress(pinchProgress)
            }
            is Gesture.PinchEnd -> if (gestureUse == GestureUse.PINCH) {
                desktop.endOverviewGesture(open = if (gesture.cancelled) pinchStartedOpen else pinchProgress > 0.5)
            }
            else -> {}
        }
        if (gestureUse == GestureUse.PROGRAM || gesture is Gesture.HoldBegin || gesture is Gesture.HoldEnd) pointerGestures.send(gesture)
        if (gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd) gestureUse = GestureUse.NONE
    }

    // The main direction of a finished swipe, if it went far enough to mean something.
    private fun swipeDirection(): Direction? {
        val horizontal = Math.abs(swipeX) >= Math.abs(swipeY)
        val distance = if (horizontal) Math.abs(swipeX) else Math.abs(swipeY)
        if (distance < SWIPE_MIN_PIXELS) return null
        return when {
            horizontal && swipeX < 0 -> Direction.LEFT
            horizontal -> Direction.RIGHT
            swipeY < 0 -> Direction.UP
            else -> Direction.DOWN
        }
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
        /** How far the fingers must move for a swipe to count. */
        const val SWIPE_MIN_PIXELS = 80.0

        /** How long a scratchpad's program may take to show a window before a key press starts it again. */
        const val STARTING_NANOS = 5_000_000_000L
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
    }
}

/** Milliseconds on the clock Wayland and libinput timestamps use. */
internal fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
