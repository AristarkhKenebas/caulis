package dev.caulis.core.input

import dev.caulis.core.Surface
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlKeyboard
import dev.caulis.protocol.wayland.WlPointer
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.wayland.WlTouch
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

    /** True if [client] has the keyboard or the pointer: the program the user uses now. */
    fun inUse(client: dev.caulis.protocol.Client): Boolean =
        keyboardFocus?.resource?.client === client || pointerFocus?.resource?.client === client

    private val keyboardFocusListeners = ArrayList<(Surface?) -> Unit>()

    /** Runs [listener] whenever the keyboard focus moves (e.g. to send the clipboard to the new client). */
    fun onKeyboardFocus(listener: (Surface?) -> Unit) {
        keyboardFocusListeners += listener
    }

    /** Held keys repeat this many times a second (0: never; 1 to 100). Programs repeat keys themselves; this tells them how. */
    var repeatRate = REPEAT_RATE
        set(value) {
            require(value in 0..100) { "repeatRate is 0 to 100 a second, not $value" }
            field = value
            repeatChanged()
        }

    /** How long a key is held before it repeats, in milliseconds (100 to 2000). */
    var repeatDelay = REPEAT_DELAY
        set(value) {
            require(value in 100..2000) { "repeatDelay is 100 to 2000 ms, not $value" }
            field = value
            repeatChanged()
        }

    private fun repeatChanged() {
        for (keyboard in keyboards) if (keyboard.version >= 4) keyboard.sendRepeatInfo(repeatRate, repeatDelay)
    }

    /** The surface under the pointer, or null. */
    var pointerFocus: Surface? = null
        private set

    /**
     * Which surfaces may get the keyboard and the pointer at all (all by default). While the
     * session is locked only the locker's surfaces may: whatever else asks for the focus then
     * gets nothing, and keys never reach a surface this says no to — even one that had the
     * focus before.
     */
    var mayFocus: (Surface) -> Boolean = { true }
        set(value) {
            field = value
            keyboardFocus?.let { if (!value(it)) focusKeyboard(null) }
            pointerFocus?.let { if (!value(it)) pointerMotion(0, null, 0.0, 0.0) }
        }

    /** The cursor picture the client under the pointer drew itself (null = none: see [cursorShape]). */
    var cursorSurface: Surface? = null
        private set
    var cursorHotspotX = 0
        private set
    var cursorHotspotY = 0
        private set

    /**
     * The cursor the client under the pointer asked for by name ("default", "text",
     * "pointer", ... see [dev.caulis.core.cursor.CursorTheme.SHAPES]), drawn from the cursor
     * theme. Null if it drew its own ([cursorSurface]) or hid the cursor.
     */
    var cursorShape: String? = "default"
        private set

    /** Called when the cursor picture changes (the window manager redraws it). */
    var onCursorChange: () -> Unit = {}

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
            if (id.version >= 4) id.sendRepeatInfo(repeatRate, repeatDelay)
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
            cursorShape = null
            onCursorChange()
        }

        override fun release(resource: WlPointer) {}
    }

    // A client asks for a cursor by name (cursor-shape-v1); only the one under the pointer may.
    internal fun setCursorShape(client: Client, shape: String) {
        if (pointerFocus?.resource?.client !== client) return
        cursorSurface = null
        cursorShape = shape
        onCursorChange()
    }

    // --- keyboard ---------------------------------------------------------------------

    /** Gives the keyboard to `surface` (null = nobody). */
    fun focusKeyboard(wanted: Surface?) {
        val surface = wanted?.takeIf(mayFocus)
        val old = keyboardFocus
        if (old === surface) return
        focusLog.trace { "Keyboard: ${old?.roleObject ?: old} → ${surface?.roleObject ?: surface}${if (wanted != null && surface == null) " (refused: $wanted may not have it now)" else ""}" }
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
        val focus = keyboardFocus?.takeIf(mayFocus) ?: return
        val serial = display.nextSerial()
        val state = if (pressed) WlKeyboard.KeyState.PRESSED else WlKeyboard.KeyState.RELEASED
        for (keyboard in keyboardsOf(focus.resource.client)) {
            keyboard.sendKey(serial, timeMillis, evdevCode, state)
            if (modifiersChanged) sendModifiers(keyboard)
        }
    }

    /** The character [evdevCode] types now (layout, Shift, AltGr), or null: none (for the compositor's own text fields). */
    fun textOf(evdevCode: Int): String? = keyboardState.text(evdevCode)

    /** The modifiers held or locked now (xkb bits: 1 Shift, 4 Ctrl, 8 Alt, 64 Logo). */
    val modifierBits: Int get() = keyboardState.modifiers.let { it.depressed or it.latched or it.locked }

    /** Releases every key, e.g. when Caulis's own window loses the keyboard. */
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
    /** Ends a group of pointer events for the program under the pointer (after relative motion alone). */
    fun pointerFrame() {
        val focus = pointerFocus ?: return
        pointersOf(focus.resource.client).forEach { it.sendFrame() }
    }

    fun pointerMotion(timeMillis: Int, under: Surface?, unitX: Double, unitY: Double) {
        val surface = under?.takeIf { it.resource.isAlive && mayFocus(it) }
        // Surfaces in screen pixels (Xwayland's) get the position in their pixels.
        val pixels = surface?.nativeScale ?: 1.0
        val x = unitX * pixels
        val y = unitY * pixels
        val old = pointerFocus
        if (old !== surface) {
            focusLog.trace { "Pointer: ${old?.roleObject ?: old} → ${surface?.roleObject ?: surface}" }
            if (old != null && old.resource.isAlive) {
                val serial = display.nextSerial()
                pointersOf(old.resource.client).forEach {
                    it.sendLeave(serial, old.resource)
                    it.sendFrame()
                }
            }
            pointerFocus = surface
            cursorSurface = null
            cursorShape = "default"
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
        val focus = pointerFocus?.takeIf(mayFocus) ?: return
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
        val focus = pointerFocus?.takeIf(mayFocus) ?: return
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

        /** Key repeat by default: characters per second and delay before repeating, in milliseconds. */
        const val REPEAT_RATE = 30
        const val REPEAT_DELAY = 300
    }
}

private val focusLog = dev.caulis.log.Logger("focus")
