package dev.caulis.core.input

import dev.caulis.core.Surface
import dev.caulis.log.Logger
import dev.caulis.protocol.Display
import dev.caulis.protocol.keyboardshortcutsinhibitunstablev1.ZwpKeyboardShortcutsInhibitManagerV1
import dev.caulis.protocol.keyboardshortcutsinhibitunstablev1.ZwpKeyboardShortcutsInhibitorV1

/**
 * keyboard-shortcuts-inhibit: a window that wants every key for itself — a compositor running
 * in a window (one you are making), a virtual machine, a remote desktop — asks for it. While
 * it has the keyboard, the compositor's own keys go to it ([active]); [restore] (a key the
 * user presses) takes them back until the window gets the keyboard again.
 */
class ShortcutsInhibit(display: Display, private val seat: Seat) {

    private val log = Logger("keys")
    private val inhibitors = HashMap<Surface, ZwpKeyboardShortcutsInhibitorV1>()

    // The window the user took the keys back from (until it loses the keyboard).
    private var restoredFor: Surface? = null

    // The inhibitor told it is active.
    private var activeOne: ZwpKeyboardShortcutsInhibitorV1? = null

    /** True while the window with the keyboard keeps the compositor's keys for itself. */
    val active: Boolean get() = activeOne != null

    private val watched = HashSet<Surface>()

    init {
        display.addGlobal(ZwpKeyboardShortcutsInhibitManagerV1, VERSION) { manager ->
            manager.requests = object : ZwpKeyboardShortcutsInhibitManagerV1.Requests {
                override fun inhibitShortcuts(resource: ZwpKeyboardShortcutsInhibitManagerV1, id: ZwpKeyboardShortcutsInhibitorV1, surface: dev.caulis.protocol.wayland.WlSurface, seat: dev.caulis.protocol.wayland.WlSeat) {
                    val target = surface.dataAs<Surface>() ?: return
                    if (target in inhibitors) {
                        resource.postError(ZwpKeyboardShortcutsInhibitManagerV1.Error.ALREADY_INHIBITED, "this surface already keeps the shortcuts")
                        return
                    }
                    inhibitors[target] = id
                    log.info { "${id.client.name} keeps the compositor's keys while it has the keyboard" }
                    id.requests = object : ZwpKeyboardShortcutsInhibitorV1.Requests {
                        override fun destroy(resource: ZwpKeyboardShortcutsInhibitorV1) = forget(target, resource)
                    }
                    id.onDestroy { forget(target, id) }
                    // Once per surface (inhibitors come and go; listeners on it would pile up).
                    if (watched.add(target)) target.resource.onDestroy {
                        inhibitors[target]?.let { forget(target, it) }
                        watched.remove(target)
                    }
                    update()
                }

                override fun destroy(resource: ZwpKeyboardShortcutsInhibitManagerV1) {}
            }
        }
        seat.onKeyboardFocus { surface ->
            if (surface !== restoredFor) restoredFor = null
            update()
        }
    }

    /** Takes the keys back from the window that has them (until it gets the keyboard again). */
    fun restore() {
        val surface = seat.keyboardFocus ?: return
        if (inhibitors[surface] == null) return
        restoredFor = surface
        log.info { "The compositor's keys are back (they were kept by a window)" }
        update()
    }

    private fun forget(surface: Surface, inhibitor: ZwpKeyboardShortcutsInhibitorV1) {
        if (inhibitors[surface] === inhibitor) inhibitors -= surface
        if (activeOne === inhibitor) activeOne = null
        update()
    }

    // The inhibitor of the window with the keyboard is active, every other one is not.
    private fun update() {
        val focus = seat.keyboardFocus
        val wanted = focus?.takeIf { it !== restoredFor }?.let { inhibitors[it] }?.takeIf { it.isAlive }
        if (wanted === activeOne) return
        activeOne?.takeIf { it.isAlive }?.sendInactive()
        activeOne = wanted
        wanted?.sendActive()
    }

    companion object {
        const val VERSION = 1
    }
}
