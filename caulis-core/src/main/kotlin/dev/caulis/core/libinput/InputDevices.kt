package dev.caulis.core.libinput

import dev.caulis.core.input.Gesture
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
    var onPointerMotion: (timeMillis: Int, dx: Double, dy: Double) -> Unit = { _, _, _ -> }

    /** Absolute position (touch screens, tablets, virtual machines) as 0..1 of the screen. */
    var onPointerMotionAbsolute: (timeMillis: Int, x: Double, y: Double) -> Unit = { _, _, _ -> }
    var onPointerButton: (timeMillis: Int, button: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    var onPointerAxis: (timeMillis: Int, vertical: Boolean, value: Double, value120: Int) -> Unit = { _, _, _, _ -> }

    /** Touchpad gestures with several fingers (swipe, pinch, hold). */
    var onGesture: (Gesture) -> Unit = {}

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
            Input.LIBINPUT_EVENT_DEVICE_ADDED() -> configure(device)
            Input.LIBINPUT_EVENT_DEVICE_REMOVED() -> releaseHeld(device)

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
            val value = Input.libinput_event_pointer_get_scroll_value(pointer, axis)
            val value120 = if (wheel) Input.libinput_event_pointer_get_scroll_value_v120(pointer, axis).toInt() else 0
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

    // Sensible defaults for new devices: tap-to-click on touchpads.
    private fun configure(device: MemorySegment) {
        if (Input.libinput_device_config_tap_get_finger_count(device) > 0) {
            Input.libinput_device_config_tap_set_enabled(device, Input.LIBINPUT_CONFIG_TAP_ENABLED())
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
