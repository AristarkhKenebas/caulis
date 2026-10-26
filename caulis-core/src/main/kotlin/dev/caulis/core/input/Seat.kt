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
class Seat(private val display: Display, keymap: Keymap) : AutoCloseable {

    /** The keymap programs are given (see [setKeymap]). */
    var keymap: Keymap = keymap
        private set

    private val keyboards = ArrayList<WlKeyboard>()
    private val pointers = ArrayList<WlPointer>()
    private val touches = ArrayList<WlTouch>()
    private val seats = ArrayList<WlSeat>()

    /** A touch screen is there: programs are told the seat can touch (and get [touchDown] and the rest). */
    var touchScreen = false
        set(value) {
            if (field == value) return
            field = value
            seats.filter { it.isAlive }.forEach { it.sendCapabilities(capabilities()) }
        }

    private fun capabilities() = WlSeat.Capability.POINTER or WlSeat.Capability.KEYBOARD or (if (touchScreen) WlSeat.Capability.TOUCH else 0)
    private var keyboardState = keymap.newState()
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
            seats += seat
            seat.onDestroy { seats -= seat }
            seat.sendCapabilities(capabilities())
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
            touches += id
            id.onDestroy { touches -= id }
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

    /**
     * Gives programs another keymap (other layouts, options): every keyboard gets it at once.
     * Num Lock and the layout in use stay as they were (the layout if the new keymap has it).
     * The old keymap is the caller's to close.
     */
    fun setKeymap(next: Keymap) {
        val before = keyboardState.modifiers
        val state = next.newState()
        state.setLocks(before.locked, if (before.group < next.layoutCount) before.group else 0)
        keyboardState.close()
        keyboardState = state
        keymap = next
        for (keyboard in keyboards) keyboard.sendKeymap(WlKeyboard.KeymapFormat.XKB_V1, next.shared.fd, next.sharedSize)
        keyboardFocus?.let { focus -> for (keyboard in keyboardsOf(focus.resource.client)) sendModifiers(keyboard) }
    }

    /** The keyboard layout in use: 0 for the keymap's first ("us" of "us,ru"). */
    val layoutIndex: Int get() = keyboardState.modifiers.group

    /** How many layouts the keymap has. */
    val layoutCount: Int get() = keymap.layoutCount

    /** Switches to the layout [index] (counting around: one past the last is the first). */
    fun setLayout(index: Int) {
        if (!keyboardState.setLocks(keyboardState.modifiers.locked, Math.floorMod(index, layoutCount.coerceAtLeast(1)))) return
        keyboardFocus?.let { focus -> for (keyboard in keyboardsOf(focus.resource.client)) sendModifiers(keyboard) }
    }

    /** True if programs were told [evdevCode] is pressed (and not yet that it was let go). */
    fun holds(evdevCode: Int): Boolean = evdevCode in pressedKeys

    /**
     * A key the compositor kept for itself (a binding): the keyboard's state follows it all
     * the same — a layout switch on it ("grp:win_space_toggle"), Caps Lock — but no program
     * gets the key.
     */
    fun trackKey(evdevCode: Int, pressed: Boolean) {
        if (!keyboardState.update(evdevCode, pressed)) return
        keyboardFocus?.let { focus -> for (keyboard in keyboardsOf(focus.resource.client)) sendModifiers(keyboard) }
    }

    /** Turns Num Lock on or off, as if its key was pressed (the program with the keyboard is told). */
    fun setNumLock(on: Boolean) {
        if (!keyboardState.setNumLock(on)) return
        val focus = keyboardFocus ?: return
        for (keyboard in keyboardsOf(focus.resource.client)) sendModifiers(keyboard)
    }

    /** The character [evdevCode] types now (layout, Shift, AltGr), or null: none (for the compositor's own text fields). */
    fun textOf(evdevCode: Int): String? = keyboardState.text(evdevCode)

    /** The name of the symbol [evdevCode] means for shortcuts: Latin whatever the layout ("d" for the key of "в"; see [KeyboardState.shortcutSymbol]). */
    fun shortcutName(evdevCode: Int): String = keySymbolName(keyboardState.shortcutSymbol(evdevCode, layoutCount))

    /** The name of the symbol [evdevCode] gives now ("h", "H", "Return", "XF86AudioMute"). */
    fun keysymName(evdevCode: Int): String = keySymbolName(keyboardState.symbol(evdevCode))

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

    // --- touch screens: each finger by its [id], at a place of the surface it went down on

    /** The program of [surface] listens to touches (else the compositor lets the finger be the pointer). */
    fun takesTouch(surface: Surface): Boolean = touches.any { it.client === surface.resource.client }

    // The surface each finger went down on.
    private val touchSurfaces = HashMap<Int, Surface>()

    /** A finger [id] touches [surface] at ([unitX], [unitY]) of it. */
    fun touchDown(timeMillis: Int, surface: Surface, id: Int, unitX: Double, unitY: Double) {
        if (!surface.resource.isAlive) return
        touchSurfaces[id] = surface
        val pixels = surface.nativeScale ?: 1.0
        val serial = display.nextSerial()
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendDown(serial, timeMillis, surface.resource, id, unitX * pixels, unitY * pixels) }
    }

    /** Finger [id] moved to ([unitX], [unitY]) of the surface it went down on. */
    fun touchMotion(timeMillis: Int, id: Int, unitX: Double, unitY: Double) {
        val surface = touchSurfaces[id]?.takeIf { it.resource.isAlive } ?: return
        val pixels = surface.nativeScale ?: 1.0
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendMotion(timeMillis, id, unitX * pixels, unitY * pixels) }
    }

    /** Finger [id] lifted. */
    fun touchUp(timeMillis: Int, id: Int) {
        val surface = touchSurfaces.remove(id)?.takeIf { it.resource.isAlive } ?: return
        val serial = display.nextSerial()
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendUp(serial, timeMillis, id) }
    }

    /** The end of a group of touch events (sent to the programs that had some). */
    fun touchFrame() {
        framePending.forEach { client -> touchesOf(client).forEach { it.sendFrame() } }
        framePending.clear()
    }

    // Programs sent touch events since the last frame.
    private val framePending = LinkedHashSet<Client>()

    /** The touches are not the programs' any more (a gesture of the compositor took them). */
    fun touchCancel() {
        touchSurfaces.values.map { it.resource.client }.distinct().forEach { client -> touchesOf(client).forEach { it.sendCancel() } }
        touchSurfaces.clear()
        framePending.clear()
    }

    private fun touchesOf(client: Client) = touches.filter { it.client === client && it.isAlive }

    override fun close() = keyboardState.close()

    companion object {
        const val VERSION = 9

        /** Key repeat by default: characters per second and delay before repeating, in milliseconds. */
        const val REPEAT_RATE = 30
        const val REPEAT_DELAY = 300
    }
}

private val focusLog = dev.caulis.log.Logger("focus")
