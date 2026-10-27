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

    /**
     * True if [client] has the keyboard, the pointer or the pen. The pointer counts even when
     * it merely passes over a program: to let a program act for the user (open a menu that
     * takes the keyboard, set the clipboard, start a drag) check [isInputSerial] instead.
     */
    fun inUse(client: dev.caulis.protocol.Client): Boolean =
        keyboardFocus?.resource?.client === client || pointerFocus?.resource?.client === client || penFocus?.resource?.client === client

    /**
     * True if [client] may set the clipboard (or the primary selection) now: [serial] is of
     * input the user gave it (a key, a click), or it has the keyboard. The pointer merely
     * passing over a program does not count (one in the background swapped what was copied).
     */
    fun maySetSelection(client: Client, serial: Int): Boolean =
        isInputSerial(client, serial) || keyboardFocus?.resource?.client === client

    // The serials of the last input events the user gave a program (keys, buttons, touches,
    // the keyboard coming to it), and which program that was. Moving the pointer over one is
    // not input: a program in the background that the pointer passes gets nothing here.
    private var inputClient: Client? = null
    private val inputSerials = ArrayDeque<Int>()

    // [client] was sent an input event with [serial] (see [isInputSerial]).
    private fun inputSent(client: Client, serial: Int) {
        if (inputClient !== client) {
            inputClient = client
            inputSerials.clear()
        }
        inputSerials.addLast(serial)
        while (inputSerials.size > INPUT_SERIALS) inputSerials.removeFirst()
    }

    /**
     * True if [serial] is one of the last input events (a key, a mouse button, a touch, the
     * keyboard coming to it) sent to [client], and no other program got input since: a
     * request with it really answers what the user did there. Menus that take the keyboard,
     * the clipboard and window drags check this, so a program the pointer just passes over
     * cannot act on its own.
     */
    fun isInputSerial(client: Client, serial: Int): Boolean = inputClient === client && serial in inputSerials

    // The session locks: input given before does not count any more (the program that had the
    // keyboard cannot set the clipboard behind the lock with an old serial).
    internal fun forgetInput() {
        if (inputClient != null) focusLog.info("Input given before the lock does not count any more")
        inputClient = null
        inputSerials.clear()
    }

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
     * Your own rule for which surfaces may get the keyboard and the pointer at all (all by
     * default). Whatever it says no to asks for the focus in vain (the keyboard stays where it
     * is), and keys never reach it — even if it had the focus before. The lock screen adds its
     * own rule on top (while the session is locked only the lock screen may), which this
     * cannot loosen and which unlocking does not reset.
     */
    var mayFocus: (Surface) -> Boolean = { true }
        set(value) {
            field = value
            focusRuleChanged()
        }

    // The lock screen's rule (set by Desktop): while locked only the lock's surfaces may.
    internal var lockAllows: (Surface) -> Boolean = { true }
        set(value) {
            field = value
            focusRuleChanged()
        }

    // True if [surface] may get the keyboard, the pointer or the pen now: the lock and [mayFocus] both say yes.
    internal fun allowed(surface: Surface): Boolean = lockAllows(surface) && mayFocus(surface)

    // A rule changed: whatever may not have the focus any more loses it.
    private fun focusRuleChanged() {
        keyboardFocus?.let { if (!allowed(it)) focusKeyboard(null) }
        pointerFocus?.let { if (!allowed(it)) pointerMotion(0, null, 0.0, 0.0) }
        penFocus?.let { if (!allowed(it)) penFocusLost(it) }
    }

    /** The cursor picture the client under the pointer drew itself (null = none: see [cursorShape]). */
    var cursorSurface: Surface? = null
        private set

    /**
     * The point of [cursorSurface] that is the pointer's tip, from its top left corner in the
     * surface's units: the picture is drawn this far up and left of the pointer's position.
     */
    var cursorHotspotX = 0
        private set

    /** See [cursorHotspotX]. */
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

    /**
     * The seat's name programs are told ("seat0" by default, as logind names the first seat).
     * Programs learn it when they bind the seat: set it before they start.
     */
    var name: String = "seat0"
        set(value) {
            if (value != field) focusLog.info { "The seat is called $value (programs binding it from now on hear it)" }
            field = value
        }

    // The keyboard's surface was destroyed under it: nobody has the keyboard now, and the window
    // manager decides who gets it next.
    internal var keyboardFocusDestroyed: (Surface) -> Unit = {}

    init {
        display.addGlobal(WlSeat, VERSION) { seat ->
            seat.requests = seatRequests
            seats += seat
            seat.onDestroy { seats -= seat }
            seat.sendCapabilities(capabilities())
            seat.sendName(name)
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
                val serial = display.nextSerial()
                id.sendEnter(serial, focus.resource, pressedKeysArray())
                inputSent(id.client, serial)
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

    // --- a pen over a program that takes tablet events ---------------------------------------

    /**
     * The surface a pen is over while its program gets tablet events (see [Tablets]); the
     * pointer has left it then, and this program says what the cursor looks like.
     */
    var penFocus: Surface? = null
        private set

    // Tablets hears of a surface that may not have the pen any more (the screen was locked).
    internal var penFocusLost: (Surface) -> Unit = {}

    // The pen came onto [surface] (null: away from tablet programs): the cursor is the default
    // one until that program sets its own.
    internal fun penEntered(surface: Surface?) {
        if (penFocus === surface) return
        penFocus = surface
        cursorSurface = null
        cursorShape = "default"
        if (surface != null) watchForDestruction(surface)
        onCursorChange()
    }

    // The program under the pen sets the cursor (zwp_tablet_tool_v2.set_cursor), as a pointer's would.
    internal fun setPenCursor(client: Client, surface: Surface?, hotspotX: Int, hotspotY: Int) {
        if (penFocus?.resource?.client !== client) return
        cursorSurface = surface
        cursorHotspotX = hotspotX
        cursorHotspotY = hotspotY
        cursorShape = null
        onCursorChange()
    }

    // The same by name (cursor-shape-v1 for a tablet tool).
    internal fun setPenCursorShape(client: Client, shape: String) {
        if (penFocus?.resource?.client !== client) return
        cursorSurface = null
        cursorShape = shape
        onCursorChange()
    }

    // --- keyboard ---------------------------------------------------------------------

    /**
     * Gives the keyboard to `surface` (null = nobody). A surface [mayFocus] (or the lock) refuses does not
     * get it, and the keyboard stays where it is (with the screen locked: the locker keeps it).
     */
    fun focusKeyboard(wanted: Surface?) {
        val old = keyboardFocus
        if (wanted != null && !allowed(wanted)) {
            focusLog.info { "Keyboard: ${wanted.roleObject ?: wanted} may not have it now; it stays with ${old?.roleObject ?: old ?: "nobody"}" }
            if (old == null || allowed(old)) return
        }
        val surface = wanted?.takeIf(::allowed)
        if (old === surface) return
        focusLog.trace { "Keyboard: ${old?.roleObject ?: old} → ${surface?.roleObject ?: surface}" }
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
            inputSent(surface.resource.client, serial)
            watchForDestruction(surface)
        }
        keyboardFocusListeners.forEach { it(surface) }
    }

    /** A key was pressed or released ([evdevCode] = Linux key code, e.g. 30 for A). */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        if (pressed) pressedKeys += evdevCode else pressedKeys -= evdevCode
        val modifiersChanged = keyboardState.update(evdevCode, pressed)
        val focus = keyboardFocus?.takeIf(::allowed) ?: return
        val serial = display.nextSerial()
        val state = if (pressed) WlKeyboard.KeyState.PRESSED else WlKeyboard.KeyState.RELEASED
        for (keyboard in keyboardsOf(focus.resource.client)) {
            keyboard.sendKey(serial, timeMillis, evdevCode, state)
            if (modifiersChanged) sendModifiers(keyboard)
        }
        inputSent(focus.resource.client, serial)
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

    /**
     * The modifier key [evdevCode] is with the user's keymap and options (Caps Lock is Super
     * with `caps:super`, Alt and Super swap with `altwin:swap_lalt_lwin`), or null: not one of
     * Ctrl, Alt, Shift, Super (Hyper counts as Super, Meta as Alt). For [KeyBindings.modifierOf].
     */
    fun modifierOfKey(evdevCode: Int): KeyBindings.Modifier? = when (keyboardState.symbol(evdevCode)) {
        0xffe1, 0xffe2 -> KeyBindings.Modifier.SHIFT
        0xffe3, 0xffe4 -> KeyBindings.Modifier.CTRL
        0xffe7, 0xffe8, 0xffe9, 0xffea -> KeyBindings.Modifier.ALT
        0xffeb, 0xffec, 0xffed, 0xffee -> KeyBindings.Modifier.SUPER
        else -> null
    }

    /** The modifiers held or locked now (xkb bits: 1 Shift, 4 Ctrl, 8 Alt, 64 Logo). */
    val modifierBits: Int get() = keyboardState.modifiers.let { it.depressed or it.latched or it.locked }

    /** Releases every key, e.g. when Caulis's own window loses the keyboard. */
    fun releaseAllKeys(timeMillis: Int) {
        for (code in pressedKeys.toList()) key(timeMillis, code, pressed = false)
    }

    private fun sendModifiers(keyboard: WlKeyboard) {
        val m = keyboardState.modifiers
        val serial = display.nextSerial()
        keyboard.sendModifiers(serial, m.depressed, m.latched, m.locked, m.group)
        // (Programs answer Ctrl+C with the serial of whatever came last: this too.)
        if (keyboardFocus?.resource?.client === keyboard.client) inputSent(keyboard.client, serial)
    }

    // Surfaces we already watch, so focusing one many times does not add many listeners.
    private val watched = HashSet<Surface>()

    // When a focused surface goes away, nothing should stay focused on it.
    private fun watchForDestruction(surface: Surface) {
        if (!watched.add(surface)) return
        surface.resource.onDestroy {
            watched -= surface
            if (keyboardFocus === surface) {
                // Through focusKeyboard, so the clipboard and the rest hear of it; then the
                // window manager gives the keyboard to whoever is next.
                focusLog.trace { "The surface with the keyboard was destroyed" }
                focusKeyboard(null)
                keyboardFocusDestroyed(surface)
            }
            if (pointerFocus === surface) pointerFocus = null
            if (penFocus === surface) penFocus = null
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

    /** Ends a group of pointer events for the program under the pointer (after relative motion alone). */
    fun pointerFrame() {
        val focus = pointerFocus ?: return
        pointersOf(focus.resource.client).forEach { it.sendFrame() }
    }

    /**
     * The pointer moved. [under] is what is under it now (null = nothing), [unitX], [unitY]
     * the position inside that surface. Sends leave/enter when the surface changes.
     */
    fun pointerMotion(timeMillis: Int, under: Surface?, unitX: Double, unitY: Double) {
        val surface = under?.takeIf { it.resource.isAlive && allowed(it) }
        // Surfaces in screen pixels (Xwayland's) get the position in their pixels.
        val pixels = surface?.nativeScale ?: 1.0
        val x = unitX * pixels
        val y = unitY * pixels
        // Dragging: the surface under the pointer hears of the drag, not of the pointer.
        drag?.let { current ->
            current.motion(timeMillis, surface, x, y)
            return
        }
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
            discreteRest.fill(0)
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

    // --- drag and drop -----------------------------------------------------------------

    // A drag and drop going on (wl_data_device.start_drag): the pointer's moves and the
    // button let go go to it instead of to programs' pointers.
    internal interface Drag {
        // The picture dragged along under the cursor, and where it is from the pointer.
        val icon: Surface?
        val iconX: Int
        val iconY: Int
        fun motion(timeMillis: Int, surface: Surface?, x: Double, y: Double)
        fun drop()
        fun cancel()
    }

    internal var drag: Drag? = null
        private set

    // Mouse buttons programs were told are held now.
    private val buttonsDown = HashSet<Int>()

    // True while a button is held (a drag may only start then).
    internal val buttonHeld: Boolean get() = buttonsDown.isNotEmpty()

    // [started] takes over the pointer: the program it was over hears that it left.
    internal fun startDrag(started: Drag) {
        drag?.let { old ->
            drag = null
            old.cancel()
        }
        pointerMotion(0, null, 0.0, 0.0)
        drag = started
        onCursorChange()
    }

    // Ends the drag going on (the screen locks, its program went away): nothing is dropped.
    internal fun cancelDrag() {
        val current = drag ?: return
        drag = null
        current.cancel()
        onCursorChange()
    }

    /** A mouse button changed ([button] = Linux code, e.g. 272 = left). */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed) buttonsDown += button else buttonsDown -= button
        drag?.let { current ->
            // Letting go drops what is dragged where the pointer is.
            if (!pressed && buttonsDown.isEmpty()) {
                drag = null
                current.drop()
                onCursorChange()
            }
            return
        }
        val focus = pointerFocus?.takeIf(::allowed) ?: return
        val serial = display.nextSerial()
        val state = if (pressed) WlPointer.ButtonState.PRESSED else WlPointer.ButtonState.RELEASED
        pointersOf(focus.resource.client).forEach {
            it.sendButton(serial, timeMillis, button, state)
            it.sendFrame()
        }
        inputSent(focus.resource.client, serial)
    }

    // Parts of a wheel notch not yet sent as whole steps to programs that only know whole
    // ones (wl_pointer before version 8): horizontal, vertical.
    private val discreteRest = IntArray(2)

    /**
     * Scrolling as 0.1.1 had it: [value] units along one axis, [value120] the wheel's steps in
     * 1/120 (a full notch = 120; 0: fingers).
     */
    @Deprecated("Give a Scroll (where it came from, notches in 1/120)", ReplaceWith("pointerAxis(timeMillis, Scroll(...))"))
    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        val source = if (value120 != 0) dev.caulis.core.input.Scroll.Source.WHEEL else dev.caulis.core.input.Scroll.Source.FINGER
        pointerAxis(
            timeMillis,
            if (vertical) dev.caulis.core.input.Scroll(source, vertical = value, vertical120 = value120)
            else dev.caulis.core.input.Scroll(source, horizontal = value, horizontal120 = value120),
        )
    }

    /**
     * Scrolling (see [Scroll]): one group of events (a frame) for the program under the
     * pointer, as much as its wl_pointer version knows — where the scroll came from, the
     * wheel's notches (in 1/120 or in whole steps), the fingers stopping, natural scrolling.
     */
    fun pointerAxis(timeMillis: Int, scroll: Scroll) {
        val focus = pointerFocus?.takeIf(::allowed) ?: return
        if (scroll.isEmpty) return
        val source = when (scroll.source) {
            Scroll.Source.WHEEL -> WlPointer.AxisSource.WHEEL
            Scroll.Source.FINGER -> WlPointer.AxisSource.FINGER
            Scroll.Source.CONTINUOUS -> WlPointer.AxisSource.CONTINUOUS
            Scroll.Source.WHEEL_TILT -> WlPointer.AxisSource.WHEEL_TILT
        }
        val axes = listOf(
            Triple(WlPointer.Axis.HORIZONTAL_SCROLL, scroll.horizontal, scroll.horizontal120) to scroll.stopsHorizontal,
            Triple(WlPointer.Axis.VERTICAL_SCROLL, scroll.vertical, scroll.vertical120) to scroll.stopsVertical,
        )
        // Whole steps for old programs, from the notches (and what was left of the last ones).
        val steps = IntArray(2)
        for (index in 0..1) {
            discreteRest[index] += axes[index].first.third
            steps[index] = discreteRest[index] / 120
            discreteRest[index] -= steps[index] * 120
        }
        pointersOf(focus.resource.client).forEach { pointer ->
            // A wheel tilted to the side is new in version 6; older programs hear "wheel".
            pointer.sendAxisSource(if (source == WlPointer.AxisSource.WHEEL_TILT && pointer.version < 6) WlPointer.AxisSource.WHEEL else source)
            for ((index, entry) in axes.withIndex()) {
                val (axisValues, stops) = entry
                val (axis, value, value120) = axisValues
                if (value != 0.0 || value120 != 0) {
                    pointer.sendAxisRelativeDirection(axis, if (scroll.inverted) WlPointer.AxisRelativeDirection.INVERTED else WlPointer.AxisRelativeDirection.IDENTICAL)
                    if (pointer.version >= 8) {
                        if (value120 != 0) pointer.sendAxisValue120(axis, value120)
                    } else if (steps[index] != 0) {
                        pointer.sendAxisDiscrete(axis, steps[index])
                    }
                    pointer.sendAxis(timeMillis, axis, value)
                } else if (stops) {
                    pointer.sendAxisStop(timeMillis, axis)
                }
            }
            // Before version 5 there are no frames: each axis event stands alone.
            pointer.sendFrame()
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
        inputSent(surface.resource.client, serial)
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
        inputSent(surface.resource.client, serial)
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

        // How many of a program's last input serials still count (see isInputSerial): enough
        // for a program that answers a little late, after a few more keys.
        private const val INPUT_SERIALS = 16

        /** Key repeat by default: characters per second and delay before repeating, in milliseconds. */
        const val REPEAT_RATE = 30
        const val REPEAT_DELAY = 300
    }
}

private val focusLog = dev.caulis.log.Logger("focus")
