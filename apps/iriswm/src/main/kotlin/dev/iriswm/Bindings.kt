package dev.iriswm

import dev.caulis.core.Desktop
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.KeyBindings.Modifier

/**
 * irisWM's keys and mouse actions. Everything that reaches programs goes through here
 * first, so irisWM's own combinations never leak into them.
 */
class Bindings(
    private val desktop: Desktop,
    quit: () -> Unit,
    openTerminal: () -> Unit,
    /** Null where switching text consoles is impossible (nested mode). */
    switchConsole: ((Int) -> Unit)?,
) {
    val keys = KeyBindings().apply {
        bind("Ctrl+Alt+BackSpace") { quit() }
        bind("Super+Return") { openTerminal() }
        bind("Super+Q") { desktop.closeFocused() }
        bind("Super+F") { desktop.focusedWindow?.let { desktop.setFullscreen(it, !it.fullscreen) } }
        bind("Super+Up") { desktop.focusedWindow?.let { desktop.setMaximized(it, !it.maximized) } }
        bind("Super+Down") { desktop.focusedWindow?.let(desktop::minimize) }
        bind("Alt+Tab") { desktop.cycleFocus() }
        if (switchConsole != null) {
            for (number in 1..12) bind("Ctrl+Alt+F$number") { switchConsole(number) }
        }
    }

    /** A key from the keyboard: irisWM's combination, or passed to the focused program. */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        if (!keys.key(evdevCode, pressed)) desktop.key(timeMillis, evdevCode, pressed)
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed && keys.isHeld(Modifier.SUPER)) {
            val window = desktop.windowAt(desktop.pointerX, desktop.pointerY)
            if (window != null && (button == BTN_LEFT || button == BTN_RIGHT)) {
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

    private companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
    }
}
