package dev.caulis.core.libinput

import dev.caulis.core.input.AccelProfile
import dev.caulis.core.input.ClickMethod
import dev.caulis.core.input.Gesture
import dev.caulis.core.input.InputSettings
import dev.caulis.core.input.ScrollMethod
import dev.caulis.core.input.TapButtons
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
 * Keyboards, mice and touchpads (through libinput), opened via the [session].
 *
 * Devices come and go at any time. When one disappears while keys or buttons on it are
 * held down, they are reported as released, so nothing stays "stuck" (a pulled-out
 * keyboard with Ctrl held must not leave Ctrl pressed forever).
 */
class InputDevices(private val loop: EventLoop, private val session: Session) : AutoCloseable {

    /** Input, in Linux key/button codes; times are milliseconds. */
    var onKey: (timeMillis: Int, evdevCode: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    /** The pointer moved by dx, dy (accelerated) and rawDx, rawDy (the mouse's own movement). */
    var onPointerMotion: (timeMillis: Int, dx: Double, dy: Double, rawDx: Double, rawDy: Double) -> Unit = { _, _, _, _, _ -> }

    /** Absolute position (touch screens, tablets, virtual machines) as 0..1 of the screen. */
    var onPointerMotionAbsolute: (timeMillis: Int, x: Double, y: Double) -> Unit = { _, _, _ -> }
    var onPointerButton: (timeMillis: Int, button: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    var onPointerAxis: (timeMillis: Int, vertical: Boolean, value: Double, value120: Int) -> Unit = { _, _, _, _ -> }

    /** Touchpad gestures with several fingers (swipe, pinch, hold). */
    var onGesture: (Gesture) -> Unit = {}

    /** A switch changed: the laptop's lid ([Switch.LID], on = closed) or tablet mode. */
    var onSwitch: (Switch, on: Boolean) -> Unit = { _, _ -> }

    /** Devices came or went (for listing them). */
    var onDevicesChanged: () -> Unit = {}

    /** How pointers and touchpads behave; applied to those there are and those that come. */
    var settings: InputSettings = InputSettings()
        set(value) {
            field = value
            for (device in devices.values) configure(device)
        }

    /**
     * Touchpads off by hand (a laptop's touchpad key), whatever the settings say; true again
     * gives them back as the settings say.
     */
    var touchpadsOn = true
        set(value) {
            if (field == value) return
            field = value
            log.info { "Touchpads ${if (value) "on" else "off"}" }
            for (device in devices.values) configure(device)
        }

    /** True if there is a touchpad at all. */
    val hasTouchpad: Boolean get() = devices.values.any { InputDevice.Kind.TOUCHPAD in it.kinds }

    /** The devices there are now. */
    val list: List<InputDevice> get() = devices.values.toList()

    // By the address of libinput's device.
    private val devices = LinkedHashMap<Long, InputDevice>()

    private val log = dev.caulis.log.Logger("input")

    private val arena = Arena.ofShared()
    private val udev: MemorySegment = Input.udev_new()
    private val context: MemorySegment
    private val fd: Fd

    // Devices libinput opened through us, by descriptor number (to close them again).
    private val opened = HashMap<Int, Session.Device>()

    // Keys and buttons held right now, per device (by the address of libinput's device).
    private val held = HashMap<Long, MutableSet<Int>>()

    init {
        check(udev != MemorySegment.NULL) { "udev_new failed" }
        val callbacks = libinput_interface.allocate(arena)
        libinput_interface.open_restricted(callbacks, libinput_interface.open_restricted.allocate({ path, _, _ ->
            Upcalls.guard("open_restricted", -1) { openDevice(CStrings.borrowed(path)) }
        }, arena))
        libinput_interface.close_restricted(callbacks, libinput_interface.close_restricted.allocate({ fd, _ ->
            Upcalls.guard("close_restricted") { opened.remove(fd)?.let(session::closeDevice) }
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
                Input.libinput_resume(context)
            } else {
                releaseEverything()
                Input.libinput_suspend(context)
            }
        }
    }

    private fun openDevice(path: String?): Int {
        if (path == null) return -1
        val device = try {
            session.openDevice(Path.of(path))
        } catch (e: Exception) {
            return -1 // libinput skips the device; everything else keeps working
        }
        opened[device.fd.value] = device
        return device.fd.value
    }

    private fun dispatch() {
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
        when (Input.libinput_event_get_type(event)) {
            Input.LIBINPUT_EVENT_DEVICE_ADDED() -> added(device)
            Input.LIBINPUT_EVENT_DEVICE_REMOVED() -> {
                releaseHeld(device)
                devices.remove(device.address())?.let { log.info { "Device gone: ${it.name}" } }
                onDevicesChanged()
            }

            Input.LIBINPUT_EVENT_SWITCH_TOGGLE() -> {
                val switch = Input.libinput_event_get_switch_event(event)
                val which = when (Input.libinput_event_switch_get_switch(switch)) {
                    Input.LIBINPUT_SWITCH_LID() -> Switch.LID
                    Input.LIBINPUT_SWITCH_TABLET_MODE() -> Switch.TABLET_MODE
                    else -> null
                }
                if (which != null) onSwitch(which, Input.libinput_event_switch_get_switch_state(switch) == Input.LIBINPUT_SWITCH_STATE_ON())
            }

            Input.LIBINPUT_EVENT_KEYBOARD_KEY() -> {
                val key = Input.libinput_event_get_keyboard_event(event)
                val code = Input.libinput_event_keyboard_get_key(key)
                val pressed = Input.libinput_event_keyboard_get_key_state(key) == Input.LIBINPUT_KEY_STATE_PRESSED()
                if (track(device, code, pressed)) onKey(Input.libinput_event_keyboard_get_time(key), code, pressed)
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                onPointerMotion(
                    Input.libinput_event_pointer_get_time(pointer),
                    Input.libinput_event_pointer_get_dx(pointer),
                    Input.libinput_event_pointer_get_dy(pointer),
                    Input.libinput_event_pointer_get_dx_unaccelerated(pointer),
                    Input.libinput_event_pointer_get_dy_unaccelerated(pointer),
                )
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION_ABSOLUTE() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                onPointerMotionAbsolute(
                    Input.libinput_event_pointer_get_time(pointer),
                    Input.libinput_event_pointer_get_absolute_x_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE,
                    Input.libinput_event_pointer_get_absolute_y_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE,
                )
            }

            Input.LIBINPUT_EVENT_POINTER_BUTTON() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val button = Input.libinput_event_pointer_get_button(pointer)
                val pressed = Input.libinput_event_pointer_get_button_state(pointer) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                if (track(device, button, pressed)) onPointerButton(Input.libinput_event_pointer_get_time(pointer), button, pressed)
            }

            Input.LIBINPUT_EVENT_GESTURE_SWIPE_BEGIN(), Input.LIBINPUT_EVENT_GESTURE_SWIPE_UPDATE(),
            Input.LIBINPUT_EVENT_GESTURE_SWIPE_END(), Input.LIBINPUT_EVENT_GESTURE_PINCH_BEGIN(),
            Input.LIBINPUT_EVENT_GESTURE_PINCH_UPDATE(), Input.LIBINPUT_EVENT_GESTURE_PINCH_END(),
            Input.LIBINPUT_EVENT_GESTURE_HOLD_BEGIN(), Input.LIBINPUT_EVENT_GESTURE_HOLD_END() -> gesture(event)

            Input.LIBINPUT_EVENT_POINTER_SCROLL_WHEEL() -> scroll(event, wheel = true)
            Input.LIBINPUT_EVENT_POINTER_SCROLL_FINGER(), Input.LIBINPUT_EVENT_POINTER_SCROLL_CONTINUOUS() -> scroll(event, wheel = false)
        }
    }

    private fun scroll(event: MemorySegment, wheel: Boolean) {
        val pointer = Input.libinput_event_get_pointer_event(event)
        val time = Input.libinput_event_pointer_get_time(pointer)
        for ((axis, vertical) in listOf(
            Input.LIBINPUT_POINTER_AXIS_SCROLL_VERTICAL() to true,
            Input.LIBINPUT_POINTER_AXIS_SCROLL_HORIZONTAL() to false,
        )) {
            if (Input.libinput_event_pointer_has_axis(pointer, axis) == 0) continue
            // The device's own scroll factor.
            val factor = devices[Input.libinput_event_get_device(event).address()]?.scrollFactor ?: 1.0
            val value = Input.libinput_event_pointer_get_scroll_value(pointer, axis) * factor
            val value120 = if (wheel) Math.round(Input.libinput_event_pointer_get_scroll_value_v120(pointer, axis) * factor).toInt() else 0
            onPointerAxis(time, vertical, value, value120)
        }
    }

    private fun gesture(event: MemorySegment) {
        val type = Input.libinput_event_get_type(event)
        val g = Input.libinput_event_get_gesture_event(event)
        val time = Input.libinput_event_gesture_get_time(g)
        val fingers by lazy { Input.libinput_event_gesture_get_finger_count(g) }
        val cancelled by lazy { Input.libinput_event_gesture_get_cancelled(g) != 0 }
        val dx by lazy { Input.libinput_event_gesture_get_dx(g) }
        val dy by lazy { Input.libinput_event_gesture_get_dy(g) }
        onGesture(
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
            },
        )
    }

    // A device came: what it is, and its settings.
    private fun added(device: MemorySegment) {
        val name = CStrings.borrowed(Input.libinput_device_get_name(device)) ?: "unknown"
        fun has(capability: Int) = Input.libinput_device_has_capability(device, capability) != 0
        val touchpad = Input.libinput_device_config_tap_get_finger_count(device) > 0
        val kinds = buildSet {
            if (has(Input.LIBINPUT_DEVICE_CAP_KEYBOARD())) add(InputDevice.Kind.KEYBOARD)
            if (has(Input.LIBINPUT_DEVICE_CAP_POINTER())) add(if (touchpad) InputDevice.Kind.TOUCHPAD else InputDevice.Kind.POINTER)
            if (has(Input.LIBINPUT_DEVICE_CAP_TOUCH())) add(InputDevice.Kind.TOUCHSCREEN)
            if (has(Input.LIBINPUT_DEVICE_CAP_TABLET_TOOL())) add(InputDevice.Kind.TABLET)
            if (has(Input.LIBINPUT_DEVICE_CAP_SWITCH())) add(InputDevice.Kind.SWITCH)
        }
        val known = InputDevice(name, kinds, device)
        devices[device.address()] = known
        log.info { "Device: $name (${kinds.joinToString { it.name.lowercase() }})" }
        configure(known)
        onDevicesChanged()
    }

    // Applies the settings that are for [device] (pointers and touchpads).
    private fun configure(device: InputDevice) {
        val d = device.handle
        val touchpad = InputDevice.Kind.TOUCHPAD in device.kinds
        if (InputDevice.Kind.POINTER !in device.kinds && !touchpad) return
        val s = settings.forDevice(device.name, touchpad)
        device.scrollFactor = s.scrollFactor ?: 1.0
        fun flag(value: Boolean?, on: Int, off: Int, set: (Int) -> Int) = value?.let { set(if (it) on else off) }
        val modes = Input.libinput_device_config_send_events_get_modes(d)
        val mode = when {
            s.enabled == false || (touchpad && !touchpadsOn) -> Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED()
            s.offWithMouse == true && modes and Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED_ON_EXTERNAL_MOUSE() != 0 -> Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED_ON_EXTERNAL_MOUSE()
            else -> Input.LIBINPUT_CONFIG_SEND_EVENTS_ENABLED()
        }
        Input.libinput_device_config_send_events_set_mode(d, mode)
        if (Input.libinput_device_config_accel_is_available(d) != 0) {
            s.speed?.let { Input.libinput_device_config_accel_set_speed(d, it) }
            s.accelProfile?.let {
                Input.libinput_device_config_accel_set_profile(d, if (it == AccelProfile.FLAT) Input.LIBINPUT_CONFIG_ACCEL_PROFILE_FLAT() else Input.LIBINPUT_CONFIG_ACCEL_PROFILE_ADAPTIVE())
            }
        }
        if (Input.libinput_device_config_scroll_has_natural_scroll(d) != 0) {
            s.naturalScroll?.let { Input.libinput_device_config_scroll_set_natural_scroll_enabled(d, if (it) 1 else 0) }
        }
        s.scrollMethod?.let { method ->
            val wanted = when (method) {
                ScrollMethod.NONE -> Input.LIBINPUT_CONFIG_SCROLL_NO_SCROLL()
                ScrollMethod.TWO_FINGER -> Input.LIBINPUT_CONFIG_SCROLL_2FG()
                ScrollMethod.EDGE -> Input.LIBINPUT_CONFIG_SCROLL_EDGE()
                ScrollMethod.BUTTON -> Input.LIBINPUT_CONFIG_SCROLL_ON_BUTTON_DOWN()
            }
            if (wanted == 0 || Input.libinput_device_config_scroll_get_methods(d) and wanted != 0) Input.libinput_device_config_scroll_set_method(d, wanted)
        }
        s.scrollButton?.let { Input.libinput_device_config_scroll_set_button(d, it) }
        if (Input.libinput_device_config_left_handed_is_available(d) != 0) s.leftHanded?.let { Input.libinput_device_config_left_handed_set(d, if (it) 1 else 0) }
        if (Input.libinput_device_config_middle_emulation_is_available(d) != 0) {
            flag(s.middleEmulation, Input.LIBINPUT_CONFIG_MIDDLE_EMULATION_ENABLED(), Input.LIBINPUT_CONFIG_MIDDLE_EMULATION_DISABLED()) { Input.libinput_device_config_middle_emulation_set_enabled(d, it) }
        }
        if (touchpad) {
            // Tapping is on unless said otherwise.
            Input.libinput_device_config_tap_set_enabled(d, if (s.tap != false) Input.LIBINPUT_CONFIG_TAP_ENABLED() else Input.LIBINPUT_CONFIG_TAP_DISABLED())
            s.tapButtons?.let {
                Input.libinput_device_config_tap_set_button_map(d, if (it == TapButtons.LEFT_RIGHT_MIDDLE) Input.LIBINPUT_CONFIG_TAP_MAP_LRM() else Input.LIBINPUT_CONFIG_TAP_MAP_LMR())
            }
            flag(s.tapDrag, Input.LIBINPUT_CONFIG_DRAG_ENABLED(), Input.LIBINPUT_CONFIG_DRAG_DISABLED()) { Input.libinput_device_config_tap_set_drag_enabled(d, it) }
            flag(s.dragLock, Input.LIBINPUT_CONFIG_DRAG_LOCK_ENABLED(), Input.LIBINPUT_CONFIG_DRAG_LOCK_DISABLED()) { Input.libinput_device_config_tap_set_drag_lock_enabled(d, it) }
            if (Input.libinput_device_config_dwt_is_available(d) != 0) {
                flag(s.disableWhileTyping, Input.LIBINPUT_CONFIG_DWT_ENABLED(), Input.LIBINPUT_CONFIG_DWT_DISABLED()) { Input.libinput_device_config_dwt_set_enabled(d, it) }
            }
            s.clickMethod?.let { method ->
                val wanted = if (method == ClickMethod.AREAS) Input.LIBINPUT_CONFIG_CLICK_METHOD_BUTTON_AREAS() else Input.LIBINPUT_CONFIG_CLICK_METHOD_CLICKFINGER()
                if (Input.libinput_device_config_click_get_methods(d) and wanted != 0) Input.libinput_device_config_click_set_method(d, wanted)
            }
        }
    }

    // Remembers what is held. Returns false for a release of something never pressed
    // (e.g. a key held while switching back from another terminal): that is not passed on.
    private fun track(device: MemorySegment, code: Int, pressed: Boolean): Boolean {
        val keys = held.getOrPut(device.address()) { HashSet() }
        return if (pressed) keys.add(code) else keys.remove(code)
    }

    private fun releaseHeld(device: MemorySegment) = releaseHeld(device.address())

    private fun releaseHeld(device: Long) {
        val keys = held.remove(device) ?: return
        val time = now()
        for (code in keys) {
            // Pointer buttons are 0x110 (BTN_LEFT) and above; keys are below.
            if (code >= BTN_MOUSE) onPointerButton(time, code, false) else onKey(time, code, false)
        }
    }

    private fun releaseEverything() {
        for (device in held.keys.toList()) releaseHeld(device)
    }

    private fun now(): Int = (System.nanoTime() / 1_000_000).toInt()

    override fun close() {
        loop.remove(fd)
        Input.libinput_unref(context)
        Input.udev_unref(udev)
        arena.close()
    }

    private companion object {
        const val ABSOLUTE_RANGE = 1_000_000
        const val BTN_MOUSE = 0x110
    }
}

/** An input device: its name (as settings name it), what it is, and libinput's handle. */
class InputDevice internal constructor(val name: String, val kinds: Set<Kind>, internal val handle: MemorySegment) {
    enum class Kind { KEYBOARD, POINTER, TOUCHPAD, TOUCHSCREEN, TABLET, SWITCH }

    // Its own scroll factor (from its settings).
    internal var scrollFactor = 1.0

    override fun toString() = "$name (${kinds.joinToString { it.name.lowercase() }})"
}

/** Switches a computer has. */
enum class Switch { LID, TABLET_MODE }
