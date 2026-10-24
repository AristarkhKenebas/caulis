package dev.tessera.core.input

import dev.tessera.core.Surface
import dev.tessera.protocol.Client
import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlKeyboard
import dev.tessera.protocol.wayland.WlPointer
import dev.tessera.protocol.wayland.WlSeat
import dev.tessera.protocol.wayland.WlSurface
import dev.tessera.protocol.wayland.WlTouch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * wl_seat: one keyboard and one pointer, as clients see them.
 *
 * The compositor decides who gets input ([focusKeyboard], [pointerMotion]); this class
 * turns that into the right events for the right client: enter/leave when focus moves,
 * keys and modifiers, motion, buttons and scrolling.
 */
class Seat(private val display: Display, private val keymap: Keymap) : AutoCloseable {

    private val keyboards = ArrayList<WlKeyboard>()
    private val pointers = ArrayList<WlPointer>()
    private val keyboardState = keymap.newState()
    private val pressedKeys = LinkedHashSet<Int>()

    /** The surface that receives key presses (a window or popup), or null. */
    var keyboardFocus: Surface? = null
        private set

    private val keyboardFocusListeners = ArrayList<(Surface?) -> Unit>()

    /** Runs [listener] whenever the keyboard focus moves (e.g. to send the clipboard to the new client). */
    fun onKeyboardFocus(listener: (Surface?) -> Unit) {
        keyboardFocusListeners += listener
    }

    /** The surface under the pointer, or null. */
    var pointerFocus: Surface? = null
        private set

    /** The cursor picture the focused client asked for (null = hidden or default). */
    var cursorSurface: Surface? = null
        private set
    var cursorHotspotX = 0
        private set
    var cursorHotspotY = 0
        private set

    /** True if the focused client wants the default cursor (it set none yet). */
    var cursorIsDefault = true
        private set

    init {
        display.addGlobal(WlSeat, VERSION) { seat ->
            seat.requests = seatRequests
            seat.sendCapabilities(WlSeat.Capability.POINTER or WlSeat.Capability.KEYBOARD)
            seat.sendName("seat0")
        }
    }

    private val seatRequests = object : WlSeat.Requests {
        override fun getPointer(resource: WlSeat, id: WlPointer) {
            pointers += id
            id.onDestroy { pointers -= id }
            id.requests = pointerRequests
        }

        override fun getKeyboard(resource: WlSeat, id: WlKeyboard) {
            keyboards += id
            id.onDestroy { keyboards -= id }
            id.requests = object : WlKeyboard.Requests {
                override fun release(resource: WlKeyboard) {}
            }
            id.sendKeymap(WlKeyboard.KeymapFormat.XKB_V1, keymap.shared.fd, keymap.sharedSize)
            id.sendRepeatInfo(REPEAT_RATE, REPEAT_DELAY)
            // A keyboard created while its client already has focus starts focused.
            val focus = keyboardFocus
            if (focus != null && focus.resource.client === id.client) {
                id.sendEnter(display.nextSerial(), focus.resource, pressedKeysArray())
                sendModifiers(id)
            }
        }

        override fun getTouch(resource: WlSeat, id: WlTouch) {
            // No touch screen yet: the object exists but never gets events.
            id.requests = object : WlTouch.Requests {
                override fun release(resource: WlTouch) {}
            }
        }

        override fun release(resource: WlSeat) {}
    }

    private val pointerRequests = object : WlPointer.Requests {
        override fun setCursor(resource: WlPointer, serial: Int, surface: WlSurface?, hotspotX: Int, hotspotY: Int) {
            // Only the client under the pointer may change the cursor.
            if (pointerFocus?.resource?.client !== resource.client) return
            val target = surface?.dataAs<Surface>()
            if (target != null && !target.assignRole(Surface.Role.CURSOR, resource, WlPointer.Error.ROLE)) return
            cursorSurface = target
            cursorHotspotX = hotspotX
            cursorHotspotY = hotspotY
            cursorIsDefault = false
        }

        override fun release(resource: WlPointer) {}
    }

    // --- keyboard ---------------------------------------------------------------------

    /** Gives the keyboard to [surface] (null = nobody). */
    fun focusKeyboard(surface: Surface?) {
        val old = keyboardFocus
        if (old === surface) return
        keyboardFocus = surface
        if (old != null && old.resource.isAlive) {
            val serial = display.nextSerial()
            keyboardsOf(old.resource.client).forEach { it.sendLeave(serial, old.resource) }
        }
        if (surface != null) {
            val serial = display.nextSerial()
            val keys = pressedKeysArray()
            for (keyboard in keyboardsOf(surface.resource.client)) {
                keyboard.sendEnter(serial, surface.resource, keys)
                sendModifiers(keyboard)
            }
            watchForDestruction(surface)
        }
        keyboardFocusListeners.forEach { it(surface) }
    }

    /** A key was pressed or released ([evdevCode] = Linux key code, e.g. 30 for A). */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        if (pressed) pressedKeys += evdevCode else pressedKeys -= evdevCode
        val modifiersChanged = keyboardState.update(evdevCode, pressed)
        val focus = keyboardFocus ?: return
        val serial = display.nextSerial()
        val state = if (pressed) WlKeyboard.KeyState.PRESSED else WlKeyboard.KeyState.RELEASED
        for (keyboard in keyboardsOf(focus.resource.client)) {
            keyboard.sendKey(serial, timeMillis, evdevCode, state)
            if (modifiersChanged) sendModifiers(keyboard)
        }
    }

    /** Releases every key, e.g. when Tessera's own window loses the keyboard. */
    fun releaseAllKeys(timeMillis: Int) {
        for (code in pressedKeys.toList()) key(timeMillis, code, pressed = false)
    }

    private fun sendModifiers(keyboard: WlKeyboard) {
        val m = keyboardState.modifiers
        keyboard.sendModifiers(display.nextSerial(), m.depressed, m.latched, m.locked, m.group)
    }

    // Surfaces we already watch, so focusing one many times does not add many listeners.
    private val watched = HashSet<Surface>()

    // When a focused surface goes away, nothing should stay focused on it.
    private fun watchForDestruction(surface: Surface) {
        if (!watched.add(surface)) return
        surface.resource.onDestroy {
            watched -= surface
            if (keyboardFocus === surface) keyboardFocus = null
            if (pointerFocus === surface) pointerFocus = null
            if (cursorSurface === surface) cursorSurface = null
        }
    }

    private fun keyboardsOf(client: Client) = keyboards.filter { it.client === client }

    private fun pressedKeysArray(): ByteArray {
        val bytes = ByteBuffer.allocate(pressedKeys.size * 4).order(ByteOrder.nativeOrder())
        pressedKeys.forEach { bytes.putInt(it) }
        return bytes.array()
    }

    // --- pointer ----------------------------------------------------------------------

    /**
     * The pointer moved. [surface] is what is under it now (null = nothing), [x], [y] the
     * position inside that surface. Sends leave/enter when the surface changes.
     */
    fun pointerMotion(timeMillis: Int, surface: Surface?, x: Double, y: Double) {
        val old = pointerFocus
        if (old !== surface) {
            if (old != null && old.resource.isAlive) {
                val serial = display.nextSerial()
                pointersOf(old.resource.client).forEach {
                    it.sendLeave(serial, old.resource)
                    it.sendFrame()
                }
            }
            pointerFocus = surface
            cursorSurface = null
            cursorIsDefault = true
            if (surface != null) {
                val serial = display.nextSerial()
                pointersOf(surface.resource.client).forEach {
                    it.sendEnter(serial, surface.resource, x, y)
                    it.sendFrame()
                }
                watchForDestruction(surface)
            }
            return
        }
        if (surface == null) return
        pointersOf(surface.resource.client).forEach {
            it.sendMotion(timeMillis, x, y)
            it.sendFrame()
        }
    }

    /** A mouse button changed ([button] = Linux code, e.g. 272 = left). */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        val focus = pointerFocus ?: return
        val serial = display.nextSerial()
        val state = if (pressed) WlPointer.ButtonState.PRESSED else WlPointer.ButtonState.RELEASED
        pointersOf(focus.resource.client).forEach {
            it.sendButton(serial, timeMillis, button, state)
            it.sendFrame()
        }
    }

    /**
     * Scrolling. [vertical] = true for the usual wheel. [value] is the distance in pixels,
     * [value120] the wheel steps in 1/120 (a full notch = 120; 0 for touchpads).
     */
    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        val focus = pointerFocus ?: return
        val axis = if (vertical) WlPointer.Axis.VERTICAL_SCROLL else WlPointer.Axis.HORIZONTAL_SCROLL
        pointersOf(focus.resource.client).forEach {
            if (value120 != 0) it.sendAxisValue120(axis, value120) // ignored by older clients
            it.sendAxis(timeMillis, axis, value)
            it.sendFrame()
        }
    }

    private fun pointersOf(client: Client) = pointers.filter { it.client === client }

    override fun close() = keyboardState.close()

    companion object {
        const val VERSION = 9

        /** Key repeat: characters per second and delay before repeating, in milliseconds. */
        const val REPEAT_RATE = 30
        const val REPEAT_DELAY = 300
    }
}
