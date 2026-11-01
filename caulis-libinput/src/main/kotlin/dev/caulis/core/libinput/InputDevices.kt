package dev.caulis.core.libinput

import dev.caulis.core.input.EarlyPointer
import dev.caulis.core.input.Gesture
import dev.caulis.core.input.InputSettings
import dev.caulis.core.input.Scroll
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletPad
import dev.caulis.core.input.TabletTool
import dev.caulis.core.session.Session
import dev.caulis.event.EventLoop
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.Fd
import dev.caulis.ffi.Upcalls
import dev.caulis.ffi.c.input.Input
import dev.caulis.ffi.c.input.libinput_interface
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Path

/**
 * Keyboards, mice, touchpads, touch screens and drawing tablets (through libinput), opened
 * via the [session].
 *
 * Devices come and go at any time. When one disappears while keys or buttons on it are
 * held down, they are reported as released, so nothing stays "stuck" (a pulled-out
 * keyboard with Ctrl held must not leave Ctrl pressed forever) — a pen near a tablet that
 * goes is lifted and taken away.
 */
class InputDevices(internal val loop: EventLoop, internal val session: Session) : AutoCloseable {
    internal val deviceSetup = InputDeviceSetup(this)
    internal val tabletInput = InputTablets(this)
    internal val inputThread = InputThread(this)

    /** Input, in Linux key/button codes; times are milliseconds. */
    var onKey: (timeMillis: Int, evdevCode: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    /** The pointer moved by dx, dy (accelerated) and rawDx, rawDy (the mouse's own movement). */
    var onPointerMotion: (timeMillis: Int, dx: Double, dy: Double, rawDx: Double, rawDy: Double) -> Unit = { _, _, _, _, _ -> }

    /** Absolute position (touch screens, tablets, virtual machines) as 0..1 of the screen. */
    var onPointerMotionAbsolute: (timeMillis: Int, x: Double, y: Double) -> Unit = { _, _, _ -> }
    var onPointerButton: (timeMillis: Int, button: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    /** Scrolling: the wheel's notches or the fingers on a touchpad, both directions in one [Scroll] (see there). */
    var onPointerAxis: (timeMillis: Int, scroll: Scroll) -> Unit = { _, _ -> }

    // Caulis 0.1.1's onPointerAxis (one axis at a time, the wheel in 1/120): code built against it sets it.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    @get:JvmName("getOnPointerAxis") @set:JvmName("setOnPointerAxis")
    var onPointerAxisPerAxis: (timeMillis: Int, vertical: Boolean, value: Double, value120: Int) -> Unit
        get() = { time, vertical, value, value120 ->
            val source = if (value120 != 0) dev.caulis.core.input.Scroll.Source.WHEEL else dev.caulis.core.input.Scroll.Source.FINGER
            onPointerAxis(
                time,
                if (vertical) dev.caulis.core.input.Scroll(source, vertical = value, vertical120 = value120)
                else dev.caulis.core.input.Scroll(source, horizontal = value, horizontal120 = value120),
            )
        }
        set(old) {
            onPointerAxis = { time, scroll ->
                if (scroll.horizontal != 0.0 || scroll.horizontal120 != 0) old(time, false, scroll.horizontal, scroll.horizontal120)
                if (scroll.vertical != 0.0 || scroll.vertical120 != 0) old(time, true, scroll.vertical, scroll.vertical120)
            }
        }

    /** Touch screens: finger [id] down or moving at (x, y) as 0..1 of the screen, lifted, a group's end, all cancelled. */
    var onTouchDown: (timeMillis: Int, id: Int, x: Double, y: Double) -> Unit = { _, _, _, _ -> }
    var onTouchMotion: (timeMillis: Int, id: Int, x: Double, y: Double) -> Unit = { _, _, _, _ -> }
    var onTouchUp: (timeMillis: Int, id: Int) -> Unit = { _, _ -> }
    var onTouchFrame: () -> Unit = {}
    var onTouchCancel: (timeMillis: Int) -> Unit = {}

    /** Touchpad gestures with several fingers (swipe, pinch, hold). */
    var onGesture: (Gesture) -> Unit = {}

    /** Drawing tablets and their pads came or went. */
    var onTabletAdded: (Tablet) -> Unit = {}
    var onTabletRemoved: (Tablet) -> Unit = {}
    var onPadAdded: (TabletPad) -> Unit = {}
    var onPadRemoved: (TabletPad) -> Unit = {}

    /**
     * A tablet's tool came near it ([near]) or went away. Its place ([x], [y]) is 0..1 of the
     * tablet's whole drawing area as it lies ([Tablet.place] makes a place on the desktop of
     * it); [axes] are all it tells when it comes, only what changed after that.
     */
    var onTabletProximity: (timeMillis: Int, tablet: Tablet, tool: TabletTool, x: Double, y: Double, axes: TabletAxes, near: Boolean) -> Unit = { _, _, _, _, _, _, _ -> }
    var onTabletMotion: (timeMillis: Int, tablet: Tablet, tool: TabletTool, x: Double, y: Double, axes: TabletAxes) -> Unit = { _, _, _, _, _, _ -> }

    /** The tool's tip touched the tablet or lifted (as libinput says, or as [dev.caulis.core.input.DeviceSettings.tipPressure] does). */
    var onTabletTip: (timeMillis: Int, tablet: Tablet, tool: TabletTool, down: Boolean) -> Unit = { _, _, _, _ -> }

    /** A button of the tool (a Linux code: 0x14b the pen's lower one) pressed or let go. */
    var onTabletButton: (timeMillis: Int, tablet: Tablet, tool: TabletTool, button: Int, pressed: Boolean) -> Unit = { _, _, _, _, _ -> }

    /** A pad's button (0 the first) pressed or let go. */
    var onPadButton: (timeMillis: Int, pad: TabletPad, button: Int, pressed: Boolean) -> Unit = { _, _, _, _ -> }

    /** A pad's ring turned to degrees (clockwise from the top; null: the finger left it), touched by a finger (false: unknown). */
    var onPadRing: (timeMillis: Int, pad: TabletPad, ring: Int, degrees: Double?, finger: Boolean) -> Unit = { _, _, _, _, _ -> }

    /** A pad's strip touched at 0..1 (null: the finger left it). */
    var onPadStrip: (timeMillis: Int, pad: TabletPad, strip: Int, position: Double?, finger: Boolean) -> Unit = { _, _, _, _, _ -> }

    /** A group of a pad switched to another mode (0 the first). */
    var onPadMode: (timeMillis: Int, pad: TabletPad, group: Int, mode: Int) -> Unit = { _, _, _, _ -> }

    /** A switch changed: the laptop's lid ([Switch.LID], on = closed) or tablet mode. */
    var onSwitch: (Switch, on: Boolean) -> Unit = { _, _ -> }

    /** Devices came or went (for listing them). */
    var onDevicesChanged: () -> Unit = {}

    /** How pointers and touchpads behave; applied to those there are and those that come. */
    @Volatile
    var settings: InputSettings = InputSettings()
        set(value) {
            field = value
            inputThread.onInputThread { for (device in devices.values) deviceSetup.configure(device) }
        }

    /**
     * Touchpads off by hand (a laptop's touchpad key), whatever the settings say; true again
     * gives them back as the settings say.
     */
    @Volatile
    var touchpadsOn = true
        set(value) {
            if (field == value) return
            field = value
            log.info { "Touchpads ${if (value) "on" else "off"}" }
            inputThread.onInputThread { for (device in devices.values) deviceSetup.configure(device) }
        }

    /** True if there is a touchpad at all. */
    val hasTouchpad: Boolean get() = published.any { InputDevice.Kind.TOUCHPAD in it.kinds }

    /** A touch screen is plugged in. */
    val hasTouchScreen: Boolean get() = published.any { InputDevice.Kind.TOUCHSCREEN in it.kinds }

    /** The devices there are now. */
    val list: List<InputDevice> get() = published

    // By the address of libinput's device. (Only on the thread that reads libinput.)
    internal val devices = LinkedHashMap<Long, InputDevice>()

    // The devices as the compositor's thread sees them (a copy made at each change).
    @Volatile
    internal var published: List<InputDevice> = emptyList()

    /**
     * Input read on a thread of its own ("caulis-input"), not on the compositor's: libinput's
     * events are read and turned into Caulis's there and queued for the compositor's thread,
     * which handles them in the same order. A busy moment of the compositor's thread then
     * holds no input back in the kernel, and the cursor (on a cursor plane) still moves:
     * [earlyPointer] is told of each movement on that thread. All of libinput is used on that
     * thread only (device settings too); opening a device asks the compositor's thread
     * (the session). False: everything on the compositor's thread, as before.
     */
    var ownThread: Boolean
        get() = inputThread.thread != null
        set(value) {
            if (value == (inputThread.thread != null)) return
            if (value) inputThread.startThread() else inputThread.stopThread()
        }

    /**
     * Told on the input thread of each relative movement before the compositor's thread hears
     * of it (only with [ownThread]): the desktop moves the cursor plane at once with it.
     */
    @Volatile
    var earlyPointer: EarlyPointer? = null

    // --- the input thread ---------------------------------------------------------------------

    internal val log = dev.caulis.log.Logger("input")

    private val arena = Arena.ofShared()
    private val udev: MemorySegment = Input.udev_new()
    private val context: MemorySegment
    internal val fd: Fd

    // Devices libinput opened through us, by descriptor number (to close them again).
    private val opened = HashMap<Int, Session.Device>()

    // Keys and buttons held right now, per device (by the address of libinput's device).
    internal val held = HashMap<Long, MutableSet<Int>>()

    // Fingers down on each touch screen right now (their seat slots), to cancel them if it goes.
    private val touching = HashMap<Long, MutableSet<Int>>()

    init {
        check(udev != MemorySegment.NULL) { "udev_new failed" }
        val callbacks = libinput_interface.allocate(arena)
        libinput_interface.open_restricted(callbacks, libinput_interface.open_restricted.allocate({ path, _, _ ->
            Upcalls.guard("open_restricted", -1) { openDevice(CStrings.borrowed(path)) }
        }, arena))
        libinput_interface.close_restricted(callbacks, libinput_interface.close_restricted.allocate({ fd, _ ->
            Upcalls.guard("close_restricted") { inputThread.toMain { opened.remove(fd)?.let(session::closeDevice) } }
        }, arena))
        context = Input.libinput_udev_create_context(callbacks, MemorySegment.NULL, udev)
        check(context != MemorySegment.NULL) { "libinput_udev_create_context failed" }
        val assigned = Arena.ofConfined().use { temp ->
            Input.libinput_udev_assign_seat(context, temp.allocateFrom(session.seatName))
        }
        check(assigned == 0) { "libinput could not use seat ${session.seatName}" }
        fd = Fd.borrow(Input.libinput_get_fd(context))
        loop.add(fd) { dispatch() }
        dispatch() // devices found at start

        // While another virtual terminal is shown, the devices belong to it.
        session.onActiveChanged { active ->
            if (active) {
                inputThread.onInputThread { Input.libinput_resume(context) }
            } else {
                // Let go before the session tells the other one it may take over: done on the
                // input thread, waited for here (the releases and the devices closed meanwhile).
                inputThread.onInputThreadAndWait {
                    releaseEverything()
                    Input.libinput_suspend(context)
                }
            }
        }
    }

    private fun openDevice(path: String?): Int {
        if (path == null) return -1
        // On the input thread: the session (libseat) is the compositor's thread's — asked there,
        // waited for (at most OPEN_WAIT_SECONDS; then the device is skipped, and closed again
        // when it was opened too late).
        if (Thread.currentThread() === inputThread.thread) {
            val answer = java.util.concurrent.CompletableFuture<Int>()
            inputThread.forMain.add {
                val fd = openDevice(path)
                if (!answer.complete(fd) && fd >= 0) opened.remove(fd)?.let(session::closeDevice)
            }
            return try {
                answer.get(OPEN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (answer.complete(-1)) {
                    log.warn("$path was not opened in time (the compositor's thread was busy): skipped")
                    -1
                } else {
                    answer.get()
                }
            }
        }
        val device = try {
            session.openDevice(Path.of(path))
        } catch (e: Exception) {
            log.warn("The input device $path cannot be opened, so it does not work: $e. Are you in a seat (logged in on this console), or in the input group with seatd?")
            return -1 // libinput skips the device; everything else keeps working
        }
        opened[device.fd.value] = device
        return device.fd.value
    }

    internal fun dispatch() {
        Input.libinput_dispatch(context)
        while (true) {
            val event = Input.libinput_get_event(context)
            if (event == MemorySegment.NULL) break
            try {
                handle(event)
            } catch (e: Exception) {
                Upcalls.report("libinput event", e) // one bad event must not stop input
            } finally {
                Input.libinput_event_destroy(event)
            }
        }
    }

    private fun handle(event: MemorySegment) {
        val device = Input.libinput_event_get_device(event)
        when (val type = Input.libinput_event_get_type(event)) {
            Input.LIBINPUT_EVENT_DEVICE_ADDED() -> deviceSetup.added(device)
            Input.LIBINPUT_EVENT_DEVICE_REMOVED() -> {
                releaseHeld(device)
                val gone = devices.remove(device.address())
                tabletInput.groups.remove(device.address())
                gone?.let { log.info { "Device gone: ${it.name}" } }
                published = devices.values.toList()
                gone?.tablet?.let { t -> inputThread.toMain { onTabletRemoved(t) } }
                gone?.pad?.let { p -> inputThread.toMain { onPadRemoved(p) } }
                inputThread.toMain { onDevicesChanged() }
            }

            Input.LIBINPUT_EVENT_TABLET_TOOL_PROXIMITY(), Input.LIBINPUT_EVENT_TABLET_TOOL_AXIS(),
            Input.LIBINPUT_EVENT_TABLET_TOOL_TIP(), Input.LIBINPUT_EVENT_TABLET_TOOL_BUTTON() -> tabletInput.tabletTool(type, event, device)

            Input.LIBINPUT_EVENT_TABLET_PAD_BUTTON(), Input.LIBINPUT_EVENT_TABLET_PAD_RING(),
            Input.LIBINPUT_EVENT_TABLET_PAD_STRIP() -> tabletInput.pad(type, event, device)

            Input.LIBINPUT_EVENT_SWITCH_TOGGLE() -> {
                val switch = Input.libinput_event_get_switch_event(event)
                val which = when (Input.libinput_event_switch_get_switch(switch)) {
                    Input.LIBINPUT_SWITCH_LID() -> Switch.LID
                    Input.LIBINPUT_SWITCH_TABLET_MODE() -> Switch.TABLET_MODE
                    else -> null
                }
                val on = Input.libinput_event_switch_get_switch_state(switch) == Input.LIBINPUT_SWITCH_STATE_ON()
                if (which != null) inputThread.toMain { onSwitch(which, on) }
            }

            Input.LIBINPUT_EVENT_KEYBOARD_KEY() -> {
                val key = Input.libinput_event_get_keyboard_event(event)
                val code = Input.libinput_event_keyboard_get_key(key)
                val pressed = Input.libinput_event_keyboard_get_key_state(key) == Input.LIBINPUT_KEY_STATE_PRESSED()
                val time = Input.libinput_event_keyboard_get_time(key)
                if (deviceSetup.track(device, code, pressed)) inputThread.toMain { onKey(time, code, pressed) }
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val time = Input.libinput_event_pointer_get_time(pointer)
                val dx = Input.libinput_event_pointer_get_dx(pointer)
                val dy = Input.libinput_event_pointer_get_dy(pointer)
                val rawDx = Input.libinput_event_pointer_get_dx_unaccelerated(pointer)
                val rawDy = Input.libinput_event_pointer_get_dy_unaccelerated(pointer)
                inputThread.motion(time, dx, dy, rawDx, rawDy)
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION_ABSOLUTE() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val time = Input.libinput_event_pointer_get_time(pointer)
                val x = Input.libinput_event_pointer_get_absolute_x_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                val y = Input.libinput_event_pointer_get_absolute_y_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                if (Thread.currentThread() === inputThread.thread) inputThread.forMain.add(inputThread.AbsoluteMotion(time, x, y)) else onPointerMotionAbsolute(time, x, y)
            }

            Input.LIBINPUT_EVENT_TOUCH_DOWN(), Input.LIBINPUT_EVENT_TOUCH_MOTION() -> {
                val touch = Input.libinput_event_get_touch_event(event)
                val time = Input.libinput_event_touch_get_time(touch)
                // (A screen of one touch at a time says -1.)
                val id = Input.libinput_event_touch_get_seat_slot(touch).coerceAtLeast(0)
                val x = Input.libinput_event_touch_get_x_transformed(touch, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                val y = Input.libinput_event_touch_get_y_transformed(touch, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                if (type == Input.LIBINPUT_EVENT_TOUCH_DOWN()) {
                    touching.getOrPut(device.address()) { HashSet() } += id
                    inputThread.toMain { onTouchDown(time, id, x, y) }
                } else {
                    inputThread.toMain { onTouchMotion(time, id, x, y) }
                }
            }

            Input.LIBINPUT_EVENT_TOUCH_UP() -> {
                val touch = Input.libinput_event_get_touch_event(event)
                val id = Input.libinput_event_touch_get_seat_slot(touch).coerceAtLeast(0)
                touching[device.address()]?.remove(id)
                val time = Input.libinput_event_touch_get_time(touch)
                inputThread.toMain { onTouchUp(time, id) }
            }

            Input.LIBINPUT_EVENT_TOUCH_FRAME() -> inputThread.toMain { onTouchFrame() }

            Input.LIBINPUT_EVENT_TOUCH_CANCEL() -> {
                touching.remove(device.address())
                val time = Input.libinput_event_touch_get_time(Input.libinput_event_get_touch_event(event))
                inputThread.toMain { onTouchCancel(time) }
            }

            Input.LIBINPUT_EVENT_POINTER_BUTTON() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val button = Input.libinput_event_pointer_get_button(pointer)
                val pressed = Input.libinput_event_pointer_get_button_state(pointer) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                val time = Input.libinput_event_pointer_get_time(pointer)
                if (deviceSetup.track(device, button, pressed)) inputThread.toMain { onPointerButton(time, button, pressed) }
            }

            Input.LIBINPUT_EVENT_GESTURE_SWIPE_BEGIN(), Input.LIBINPUT_EVENT_GESTURE_SWIPE_UPDATE(),
            Input.LIBINPUT_EVENT_GESTURE_SWIPE_END(), Input.LIBINPUT_EVENT_GESTURE_PINCH_BEGIN(),
            Input.LIBINPUT_EVENT_GESTURE_PINCH_UPDATE(), Input.LIBINPUT_EVENT_GESTURE_PINCH_END(),
            Input.LIBINPUT_EVENT_GESTURE_HOLD_BEGIN(), Input.LIBINPUT_EVENT_GESTURE_HOLD_END() -> gesture(event)

            Input.LIBINPUT_EVENT_POINTER_SCROLL_WHEEL() -> scroll(event, Scroll.Source.WHEEL)
            Input.LIBINPUT_EVENT_POINTER_SCROLL_FINGER() -> scroll(event, Scroll.Source.FINGER)
            Input.LIBINPUT_EVENT_POINTER_SCROLL_CONTINUOUS() -> scroll(event, Scroll.Source.CONTINUOUS)
        }
    }

    // One scroll event of libinput: both directions together (a diagonal swipe stays one). A
    // finger or continuous scroll that says 0 on a direction it has means the fingers stopped
    // there (programs end their kinetic scrolling).
    private fun scroll(event: MemorySegment, source: Scroll.Source) {
        val pointer = Input.libinput_event_get_pointer_event(event)
        val time = Input.libinput_event_pointer_get_time(pointer)
        // The device's own scroll factor.
        val factor = devices[Input.libinput_event_get_device(event).address()]?.scrollFactor ?: 1.0
        val values = DoubleArray(2)
        val steps = IntArray(2)
        val stops = BooleanArray(2)
        for ((index, axis) in listOf(Input.LIBINPUT_POINTER_AXIS_SCROLL_HORIZONTAL(), Input.LIBINPUT_POINTER_AXIS_SCROLL_VERTICAL()).withIndex()) {
            if (Input.libinput_event_pointer_has_axis(pointer, axis) == 0) continue
            values[index] = Input.libinput_event_pointer_get_scroll_value(pointer, axis) * factor
            if (source == Scroll.Source.WHEEL) {
                steps[index] = Math.round(Input.libinput_event_pointer_get_scroll_value_v120(pointer, axis) * factor).toInt()
            } else if (values[index] == 0.0) {
                stops[index] = true
            }
        }
        val scroll = Scroll(source, values[0], values[1], steps[0], steps[1], stops[0], stops[1])
        inputThread.toMain { onPointerAxis(time, scroll) }
    }

    private fun gesture(event: MemorySegment) {
        val type = Input.libinput_event_get_type(event)
        val g = Input.libinput_event_get_gesture_event(event)
        val time = Input.libinput_event_gesture_get_time(g)
        val fingers by lazy { Input.libinput_event_gesture_get_finger_count(g) }
        val cancelled by lazy { Input.libinput_event_gesture_get_cancelled(g) != 0 }
        val dx by lazy { Input.libinput_event_gesture_get_dx(g) }
        val dy by lazy { Input.libinput_event_gesture_get_dy(g) }
        val gesture = (
            when (type) {
                Input.LIBINPUT_EVENT_GESTURE_SWIPE_BEGIN() -> Gesture.SwipeBegin(time, fingers)
                Input.LIBINPUT_EVENT_GESTURE_SWIPE_UPDATE() -> Gesture.SwipeUpdate(time, dx, dy)
                Input.LIBINPUT_EVENT_GESTURE_SWIPE_END() -> Gesture.SwipeEnd(time, cancelled)
                Input.LIBINPUT_EVENT_GESTURE_PINCH_BEGIN() -> Gesture.PinchBegin(time, fingers)
                Input.LIBINPUT_EVENT_GESTURE_PINCH_UPDATE() ->
                    Gesture.PinchUpdate(time, dx, dy, Input.libinput_event_gesture_get_scale(g), Input.libinput_event_gesture_get_angle_delta(g))
                Input.LIBINPUT_EVENT_GESTURE_PINCH_END() -> Gesture.PinchEnd(time, cancelled)
                Input.LIBINPUT_EVENT_GESTURE_HOLD_BEGIN() -> Gesture.HoldBegin(time, fingers)
                else -> Gesture.HoldEnd(time, cancelled)
            }
        )
        inputThread.toMain { onGesture(gesture) }
    }

    private fun releaseHeld(device: MemorySegment) = releaseHeld(device.address())

    private fun releaseHeld(device: Long) {
        val time = now()
        // Fingers on a touch screen that went (or whose terminal we left): programs forget them.
        if (touching.remove(device)?.isNotEmpty() == true) {
            log.info("A touch screen went away with fingers on it: the touches are cancelled")
            inputThread.toMain { onTouchCancel(time) }
        }
        // Tools near the tablet, and pad buttons held.
        for ((tool, n) in tabletInput.near.toList()) if (n.device == device) tabletInput.away(time, tool)
        devices[device]?.pad?.let { pad -> tabletInput.padHeld.remove(pad)?.forEach { button -> inputThread.toMain { onPadButton(time, pad, button, false) } } }
        val keys = held.remove(device) ?: return
        for (code in keys) {
            // Pointer buttons are 0x110 (BTN_LEFT) and above; keys are below.
            if (code >= BTN_MOUSE) inputThread.toMain { onPointerButton(time, code, false) } else inputThread.toMain { onKey(time, code, false) }
        }
    }

    private fun releaseEverything() {
        for (device in (held.keys + tabletInput.near.values.map { it.device } + touching.keys + devices.keys).toSet()) releaseHeld(device)
    }

    private fun now(): Int = (System.nanoTime() / 1_000_000).toInt()

    override fun close() {
        inputThread.stopThread()
        loop.remove(fd)
        Input.libinput_unref(context)
        Input.udev_unref(udev)
        arena.close()
    }

}

/** An input device: its name (as settings name it), what it is, and libinput's handle. */
class InputDevice internal constructor(val name: String, val kinds: Set<Kind>, internal val handle: MemorySegment) {
    enum class Kind { KEYBOARD, POINTER, TOUCHPAD, TOUCHSCREEN, TABLET, SWITCH, TABLET_PAD }

    /** The drawing tablet it is ([Kind.TABLET]), or null. */
    var tablet: Tablet? = null
        internal set

    /** The tablet pad it is ([Kind.TABLET_PAD]), or null. */
    var pad: TabletPad? = null
        internal set

    // Its own scroll factor (from its settings).
    internal var scrollFactor = 1.0

    override fun toString() = "$name (${kinds.joinToString { it.name.lowercase() }})"
}

/** Switches a computer has. */
enum class Switch { LID, TABLET_MODE }

internal const val ABSOLUTE_RANGE = 1_000_000
internal const val OPEN_WAIT_SECONDS = 5L
internal const val PAUSE_WAIT_MILLIS = 2000L
internal const val BTN_MOUSE = 0x110
