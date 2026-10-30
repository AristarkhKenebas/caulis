package dev.caulis.core.libinput

import dev.caulis.core.input.AccelProfile
import dev.caulis.core.input.ClickMethod
import dev.caulis.core.input.EarlyPointer
import dev.caulis.core.input.Gesture
import dev.caulis.core.input.InputSettings
import dev.caulis.core.input.Scroll
import dev.caulis.core.input.ScrollMethod
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletPad
import dev.caulis.core.input.TabletTool
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
 * Keyboards, mice, touchpads, touch screens and drawing tablets (through libinput), opened
 * via the [session].
 *
 * Devices come and go at any time. When one disappears while keys or buttons on it are
 * held down, they are reported as released, so nothing stays "stuck" (a pulled-out
 * keyboard with Ctrl held must not leave Ctrl pressed forever) — a pen near a tablet that
 * goes is lifted and taken away.
 */
class InputDevices(private val loop: EventLoop, private val session: Session) : AutoCloseable {

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
            onInputThread { for (device in devices.values) configure(device) }
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
            onInputThread { for (device in devices.values) configure(device) }
        }

    /** True if there is a touchpad at all. */
    val hasTouchpad: Boolean get() = published.any { InputDevice.Kind.TOUCHPAD in it.kinds }

    /** A touch screen is plugged in. */
    val hasTouchScreen: Boolean get() = published.any { InputDevice.Kind.TOUCHSCREEN in it.kinds }

    /** The devices there are now. */
    val list: List<InputDevice> get() = published

    // By the address of libinput's device. (Only on the thread that reads libinput.)
    private val devices = LinkedHashMap<Long, InputDevice>()

    // The devices as the compositor's thread sees them (a copy made at each change).
    @Volatile
    private var published: List<InputDevice> = emptyList()

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
        get() = thread != null
        set(value) {
            if (value == (thread != null)) return
            if (value) startThread() else stopThread()
        }

    /**
     * Told on the input thread of each relative movement before the compositor's thread hears
     * of it (only with [ownThread]): the desktop moves the cursor plane at once with it.
     */
    @Volatile
    var earlyPointer: EarlyPointer? = null

    // --- the input thread ---------------------------------------------------------------------

    private var thread: Thread? = null
    private var inputLoop: EventLoop? = null

    // What the compositor's thread is to do, in order: events for the callbacks, devices to
    // open or close. One queue (FIFO), emptied by one task posted when it was empty.
    private val forMain = MainQueue(loop::post)

    // Runs [task] on the compositor's thread: at once there, else queued (in order).
    private fun toMain(task: () -> Unit) {
        if (Thread.currentThread() === thread) forMain.add(task) else task()
    }

    // Runs [task] where libinput is used: on the input thread (queued) or here.
    private fun onInputThread(task: () -> Unit) {
        val other = inputLoop
        if (other != null && Thread.currentThread() !== thread) other.post(task) else task()
    }

    // Runs [task] on the input thread and waits for it (at most PAUSE_WAIT_MILLIS), doing what
    // it hands to this thread meanwhile; here at once without one.
    private fun onInputThreadAndWait(task: () -> Unit) {
        val other = inputLoop
        if (other == null || Thread.currentThread() === thread) return task()
        val done = java.util.concurrent.CompletableFuture<Unit>()
        other.post {
            try {
                task()
            } finally {
                done.complete(Unit)
            }
        }
        val until = System.nanoTime() + PAUSE_WAIT_MILLIS * 1_000_000
        while (!done.isDone && System.nanoTime() < until) {
            forMain.runAll()
            try {
                done.get(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
            }
        }
        if (!done.isDone) log.warn("The input thread did not let go of the devices in time")
        forMain.runAll()
    }

    private fun motion(time: Int, dx: Double, dy: Double, rawDx: Double, rawDy: Double) {
        if (Thread.currentThread() !== thread) return onPointerMotion(time, dx, dy, rawDx, rawDy)
        val early = earlyPointer
        if (early != null) {
            try {
                early.movedEarly(dx, dy)
            } catch (e: Exception) {
                Upcalls.report("early pointer", e)
            }
        }
        forMain.add(Motion(time, dx, dy, rawDx, rawDy, early))
    }

    // A relative movement waiting for the compositor's thread; the next ones still waiting
    // are summed into it (one handled, the cursor where all of them took it).
    private inner class Motion(
        private var time: Int, private var dx: Double, private var dy: Double,
        private var rawDx: Double, private var rawDy: Double, private val early: EarlyPointer?,
    ) : MainQueue.Merging() {
        override fun take(next: MainQueue.Merging): Boolean {
            if (next !is Motion || next.early !== early) return false
            time = next.time
            dx += next.dx
            dy += next.dy
            rawDx += next.rawDx
            rawDy += next.rawDy
            return true
        }

        override fun run() {
            if (count > 1) log.trace { "$count pointer movements waited: handled as one" }
            // (Caught up even when handling the motion failed: else the early cursor never
            // took the compositor's place again — it jumped back after each warp.)
            try {
                onPointerMotion(time, dx, dy, rawDx, rawDy)
            } finally {
                if (early != null) {
                    try {
                        early.caughtUp(count)
                    } catch (e: Exception) {
                        Upcalls.report("early pointer", e)
                    }
                }
            }
        }
    }

    // An absolute movement (a tablet, a virtual machine's pointer) waiting: the next ones
    // still waiting replace its place (only the last place counts).
    private inner class AbsoluteMotion(private var time: Int, private var x: Double, private var y: Double) : MainQueue.Merging() {
        override fun take(next: MainQueue.Merging): Boolean {
            if (next !is AbsoluteMotion) return false
            time = next.time
            x = next.x
            y = next.y
            return true
        }

        override fun run() = onPointerMotionAbsolute(time, x, y)
    }

    private fun startThread() {
        val other = EventLoop()
        // (Its own stall reports would say "the main loop": it has none; errors are logged.)
        other.stallMillis = 0
        other.onError = { what, error -> log.error("Input thread: $what failed (it goes on): $error", error) }
        loop.remove(fd)
        other.add(fd) { dispatchBatch() }
        inputLoop = other
        val started = Thread.ofPlatform().name("caulis-input").daemon().unstarted {
            log.info { "Input is read on a thread of its own" }
            try {
                other.run()
            } catch (e: Throwable) {
                log.error("The input thread ended: $e", e)
            }
        }
        thread = started
        started.start()
        // (What came meanwhile: read there.)
        other.post { dispatchBatch() }
    }

    private fun stopThread() {
        val running = thread ?: return
        val other = inputLoop ?: return
        // Until it ended: what it waits for here (a device being opened) is done meanwhile.
        // (Told again each time: it may not have begun to run when first told.)
        while (running.isAlive) {
            other.stop()
            other.post {} // (wakes it to notice)
            forMain.runAll()
            running.join(10)
        }
        thread = null
        inputLoop = null
        forMain.runAll()
        other.remove(fd)
        other.close()
        loop.add(fd) { dispatch() }
        log.info { "Input is read on the compositor's thread" }
        dispatch()
    }

    // Reads libinput on the input thread; then the cursor is shown where the pointer is now.
    private fun dispatchBatch() {
        dispatch()
        val early = earlyPointer ?: return
        try {
            early.showEarly()
        } catch (e: Exception) {
            Upcalls.report("early pointer", e)
        }
    }

    private val log = dev.caulis.log.Logger("input")

    private val arena = Arena.ofShared()
    private val udev: MemorySegment = Input.udev_new()
    private val context: MemorySegment
    private val fd: Fd

    // Devices libinput opened through us, by descriptor number (to close them again).
    private val opened = HashMap<Int, Session.Device>()

    // Keys and buttons held right now, per device (by the address of libinput's device).
    private val held = HashMap<Long, MutableSet<Int>>()

    // Fingers down on each touch screen right now (their seat slots), to cancel them if it goes.
    private val touching = HashMap<Long, MutableSet<Int>>()

    // Tablet tools: each one by what tells it apart (see toolOf), and those near a tablet now.
    private val tools = HashMap<String, TabletTool>()
    private class Near(val tablet: Tablet, val device: Long) {
        var tip = false
        var pressure = 0.0
        var x = 0.0
        var y = 0.0
        val buttons = LinkedHashSet<Int>()
    }
    private val near = HashMap<TabletTool, Near>()

    // Each pad's groups' modes as last told, and its buttons held.
    private val padModes = HashMap<TabletPad, IntArray>()
    private val padHeld = HashMap<TabletPad, MutableSet<Int>>()

    // The device group of each device (a tablet's pen and its pad are one group).
    private val groups = HashMap<Long, Long>()

    init {
        check(udev != MemorySegment.NULL) { "udev_new failed" }
        val callbacks = libinput_interface.allocate(arena)
        libinput_interface.open_restricted(callbacks, libinput_interface.open_restricted.allocate({ path, _, _ ->
            Upcalls.guard("open_restricted", -1) { openDevice(CStrings.borrowed(path)) }
        }, arena))
        libinput_interface.close_restricted(callbacks, libinput_interface.close_restricted.allocate({ fd, _ ->
            Upcalls.guard("close_restricted") { toMain { opened.remove(fd)?.let(session::closeDevice) } }
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
                onInputThread { Input.libinput_resume(context) }
            } else {
                // Let go before the session tells the other one it may take over: done on the
                // input thread, waited for here (the releases and the devices closed meanwhile).
                onInputThreadAndWait {
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
        if (Thread.currentThread() === thread) {
            val answer = java.util.concurrent.CompletableFuture<Int>()
            forMain.add {
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
        when (val type = Input.libinput_event_get_type(event)) {
            Input.LIBINPUT_EVENT_DEVICE_ADDED() -> added(device)
            Input.LIBINPUT_EVENT_DEVICE_REMOVED() -> {
                releaseHeld(device)
                val gone = devices.remove(device.address())
                groups.remove(device.address())
                gone?.let { log.info { "Device gone: ${it.name}" } }
                published = devices.values.toList()
                gone?.tablet?.let { t -> toMain { onTabletRemoved(t) } }
                gone?.pad?.let { p -> toMain { onPadRemoved(p) } }
                toMain { onDevicesChanged() }
            }

            Input.LIBINPUT_EVENT_TABLET_TOOL_PROXIMITY(), Input.LIBINPUT_EVENT_TABLET_TOOL_AXIS(),
            Input.LIBINPUT_EVENT_TABLET_TOOL_TIP(), Input.LIBINPUT_EVENT_TABLET_TOOL_BUTTON() -> tabletTool(type, event, device)

            Input.LIBINPUT_EVENT_TABLET_PAD_BUTTON(), Input.LIBINPUT_EVENT_TABLET_PAD_RING(),
            Input.LIBINPUT_EVENT_TABLET_PAD_STRIP() -> pad(type, event, device)

            Input.LIBINPUT_EVENT_SWITCH_TOGGLE() -> {
                val switch = Input.libinput_event_get_switch_event(event)
                val which = when (Input.libinput_event_switch_get_switch(switch)) {
                    Input.LIBINPUT_SWITCH_LID() -> Switch.LID
                    Input.LIBINPUT_SWITCH_TABLET_MODE() -> Switch.TABLET_MODE
                    else -> null
                }
                val on = Input.libinput_event_switch_get_switch_state(switch) == Input.LIBINPUT_SWITCH_STATE_ON()
                if (which != null) toMain { onSwitch(which, on) }
            }

            Input.LIBINPUT_EVENT_KEYBOARD_KEY() -> {
                val key = Input.libinput_event_get_keyboard_event(event)
                val code = Input.libinput_event_keyboard_get_key(key)
                val pressed = Input.libinput_event_keyboard_get_key_state(key) == Input.LIBINPUT_KEY_STATE_PRESSED()
                val time = Input.libinput_event_keyboard_get_time(key)
                if (track(device, code, pressed)) toMain { onKey(time, code, pressed) }
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val time = Input.libinput_event_pointer_get_time(pointer)
                val dx = Input.libinput_event_pointer_get_dx(pointer)
                val dy = Input.libinput_event_pointer_get_dy(pointer)
                val rawDx = Input.libinput_event_pointer_get_dx_unaccelerated(pointer)
                val rawDy = Input.libinput_event_pointer_get_dy_unaccelerated(pointer)
                motion(time, dx, dy, rawDx, rawDy)
            }

            Input.LIBINPUT_EVENT_POINTER_MOTION_ABSOLUTE() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val time = Input.libinput_event_pointer_get_time(pointer)
                val x = Input.libinput_event_pointer_get_absolute_x_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                val y = Input.libinput_event_pointer_get_absolute_y_transformed(pointer, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
                if (Thread.currentThread() === thread) forMain.add(AbsoluteMotion(time, x, y)) else onPointerMotionAbsolute(time, x, y)
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
                    toMain { onTouchDown(time, id, x, y) }
                } else {
                    toMain { onTouchMotion(time, id, x, y) }
                }
            }

            Input.LIBINPUT_EVENT_TOUCH_UP() -> {
                val touch = Input.libinput_event_get_touch_event(event)
                val id = Input.libinput_event_touch_get_seat_slot(touch).coerceAtLeast(0)
                touching[device.address()]?.remove(id)
                val time = Input.libinput_event_touch_get_time(touch)
                toMain { onTouchUp(time, id) }
            }

            Input.LIBINPUT_EVENT_TOUCH_FRAME() -> toMain { onTouchFrame() }

            Input.LIBINPUT_EVENT_TOUCH_CANCEL() -> {
                touching.remove(device.address())
                val time = Input.libinput_event_touch_get_time(Input.libinput_event_get_touch_event(event))
                toMain { onTouchCancel(time) }
            }

            Input.LIBINPUT_EVENT_POINTER_BUTTON() -> {
                val pointer = Input.libinput_event_get_pointer_event(event)
                val button = Input.libinput_event_pointer_get_button(pointer)
                val pressed = Input.libinput_event_pointer_get_button_state(pointer) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                val time = Input.libinput_event_pointer_get_time(pointer)
                if (track(device, button, pressed)) toMain { onPointerButton(time, button, pressed) }
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

    // --- drawing tablets ------------------------------------------------------------------------

    private fun tabletTool(type: Int, event: MemorySegment, device: MemorySegment) {
        val tablet = devices[device.address()]?.tablet ?: return
        val e = Input.libinput_event_get_tablet_tool_event(event)
        val time = Input.libinput_event_tablet_tool_get_time(e)
        val tool = toolOf(Input.libinput_event_tablet_tool_get_tool(e), device)
        val x = Input.libinput_event_tablet_tool_get_x_transformed(e, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
        val y = Input.libinput_event_tablet_tool_get_y_transformed(e, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
        when (type) {
            Input.LIBINPUT_EVENT_TABLET_TOOL_PROXIMITY() -> {
                if (Input.libinput_event_tablet_tool_get_proximity_state(e) == Input.LIBINPUT_TABLET_TOOL_PROXIMITY_STATE_IN()) {
                    val n = Near(tablet, device.address())
                    near[tool] = n
                    val axes = axes(e, tool, all = true)
                    n.pressure = axes.pressure ?: 0.0
                    n.x = x
                    n.y = y
                    toMain { onTabletProximity(time, tablet, tool, x, y, axes, true) }
                } else {
                    away(time, tool)
                }
            }
            Input.LIBINPUT_EVENT_TABLET_TOOL_AXIS(), Input.LIBINPUT_EVENT_TABLET_TOOL_TIP() -> {
                val n = near[tool] ?: return
                val axes = axes(e, tool, all = false)
                axes.pressure?.let { n.pressure = it }
                if (x != n.x || y != n.y || axes != TabletAxes.NONE) {
                    n.x = x
                    n.y = y
                    toMain { onTabletMotion(time, tablet, tool, x, y, axes) }
                }
                // The tip: as libinput says, or as hard as the settings want.
                val threshold = tablet.settings.tipPressure?.takeIf { TabletTool.Capability.PRESSURE in tool.capabilities }
                val down = when {
                    threshold != null -> n.pressure >= threshold
                    type == Input.LIBINPUT_EVENT_TABLET_TOOL_TIP() -> Input.libinput_event_tablet_tool_get_tip_state(e) == Input.LIBINPUT_TABLET_TOOL_TIP_DOWN()
                    else -> n.tip
                }
                if (down != n.tip) {
                    n.tip = down
                    toMain { onTabletTip(time, tablet, tool, down) }
                }
            }
            Input.LIBINPUT_EVENT_TABLET_TOOL_BUTTON() -> {
                val n = near[tool] ?: return
                val button = Input.libinput_event_tablet_tool_get_button(e)
                val pressed = Input.libinput_event_tablet_tool_get_button_state(e) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                if (if (pressed) n.buttons.add(button) else n.buttons.remove(button)) toMain { onTabletButton(time, tablet, tool, button, pressed) }
            }
        }
    }

    // [tool] went away (or its tablet did): its tip lifted and its buttons let go first.
    private fun away(time: Int, tool: TabletTool) {
        val n = near.remove(tool) ?: return
        val tablet = n.tablet
        val x = n.x
        val y = n.y
        if (n.tip) toMain { onTabletTip(time, tablet, tool, false) }
        for (button in n.buttons.toList()) toMain { onTabletButton(time, tablet, tool, button, false) }
        toMain { onTabletProximity(time, tablet, tool, x, y, TabletAxes.NONE, false) }
    }

    // The tool libinput means: the same object for the same physical tool. One with a serial is
    // itself on every tablet; one without is a tool of its tablet.
    private fun toolOf(handle: MemorySegment, device: MemorySegment): TabletTool {
        val serial = Input.libinput_tablet_tool_get_serial(handle)
        val id = Input.libinput_tablet_tool_get_tool_id(handle)
        val type = Input.libinput_tablet_tool_get_type(handle)
        val key = if (serial != 0L) "$type:$serial:$id" else "${device.address()}:$type:$id"
        return tools.getOrPut(key) {
            val capabilities = buildSet {
                if (Input.libinput_tablet_tool_has_pressure(handle) != 0) add(TabletTool.Capability.PRESSURE)
                if (Input.libinput_tablet_tool_has_distance(handle) != 0) add(TabletTool.Capability.DISTANCE)
                if (Input.libinput_tablet_tool_has_tilt(handle) != 0) add(TabletTool.Capability.TILT)
                if (Input.libinput_tablet_tool_has_rotation(handle) != 0) add(TabletTool.Capability.ROTATION)
                if (Input.libinput_tablet_tool_has_slider(handle) != 0) add(TabletTool.Capability.SLIDER)
                if (Input.libinput_tablet_tool_has_wheel(handle) != 0) add(TabletTool.Capability.WHEEL)
            }
            val kind = when (type) {
                Input.LIBINPUT_TABLET_TOOL_TYPE_ERASER() -> TabletTool.Type.ERASER
                Input.LIBINPUT_TABLET_TOOL_TYPE_BRUSH() -> TabletTool.Type.BRUSH
                Input.LIBINPUT_TABLET_TOOL_TYPE_PENCIL() -> TabletTool.Type.PENCIL
                Input.LIBINPUT_TABLET_TOOL_TYPE_AIRBRUSH() -> TabletTool.Type.AIRBRUSH
                Input.LIBINPUT_TABLET_TOOL_TYPE_MOUSE() -> TabletTool.Type.MOUSE
                Input.LIBINPUT_TABLET_TOOL_TYPE_LENS() -> TabletTool.Type.LENS
                else -> TabletTool.Type.PEN
            }
            TabletTool(kind, capabilities, serial, id).also { log.info { "Tablet tool: ${it.type.name.lowercase()} (${capabilities.joinToString { c -> c.name.lowercase() }.ifEmpty { "no axes" }})" } }
        }
    }

    // What the tool tells besides its place: everything ([all], when it comes near) or what changed.
    private fun axes(e: MemorySegment, tool: TabletTool, all: Boolean): TabletAxes {
        fun has(c: TabletTool.Capability) = c in tool.capabilities
        fun changed(c: TabletTool.Capability, test: (MemorySegment) -> Int) = has(c) && (all || test(e) != 0)
        val tilt = changed(TabletTool.Capability.TILT) { Input.libinput_event_tablet_tool_tilt_x_has_changed(it) or Input.libinput_event_tablet_tool_tilt_y_has_changed(it) }
        val wheel = !all && has(TabletTool.Capability.WHEEL) && Input.libinput_event_tablet_tool_wheel_has_changed(e) != 0
        return TabletAxes(
            pressure = if (changed(TabletTool.Capability.PRESSURE, Input::libinput_event_tablet_tool_pressure_has_changed)) Input.libinput_event_tablet_tool_get_pressure(e) else null,
            distance = if (changed(TabletTool.Capability.DISTANCE, Input::libinput_event_tablet_tool_distance_has_changed)) Input.libinput_event_tablet_tool_get_distance(e) else null,
            // (Both together: programs get them in one event.)
            tiltX = if (tilt) Input.libinput_event_tablet_tool_get_tilt_x(e) else null,
            tiltY = if (tilt) Input.libinput_event_tablet_tool_get_tilt_y(e) else null,
            rotation = if (changed(TabletTool.Capability.ROTATION, Input::libinput_event_tablet_tool_rotation_has_changed)) Input.libinput_event_tablet_tool_get_rotation(e) else null,
            slider = if (changed(TabletTool.Capability.SLIDER, Input::libinput_event_tablet_tool_slider_has_changed)) Input.libinput_event_tablet_tool_get_slider_position(e) else null,
            wheelDegrees = if (wheel) Input.libinput_event_tablet_tool_get_wheel_delta(e) else 0.0,
            wheelClicks = if (wheel) Input.libinput_event_tablet_tool_get_wheel_delta_discrete(e) else 0,
        )
    }

    private fun pad(type: Int, event: MemorySegment, device: MemorySegment) {
        val pad = devices[device.address()]?.pad ?: return
        val e = Input.libinput_event_get_tablet_pad_event(event)
        val time = Input.libinput_event_tablet_pad_get_time(e)
        // Every pad event says the mode its group is in (a mode button switched it).
        val modes = padModes.getOrPut(pad) { IntArray(pad.groups.size) }
        val group = Input.libinput_tablet_pad_mode_group_get_index(Input.libinput_event_tablet_pad_get_mode_group(e))
        val mode = Input.libinput_event_tablet_pad_get_mode(e)
        if (group in modes.indices && modes[group] != mode) {
            modes[group] = mode
            toMain { onPadMode(time, pad, group, mode) }
        }
        when (type) {
            Input.LIBINPUT_EVENT_TABLET_PAD_BUTTON() -> {
                val button = Input.libinput_event_tablet_pad_get_button_number(e)
                val pressed = Input.libinput_event_tablet_pad_get_button_state(e) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                val buttons = padHeld.getOrPut(pad) { HashSet() }
                if (if (pressed) buttons.add(button) else buttons.remove(button)) toMain { onPadButton(time, pad, button, pressed) }
            }
            Input.LIBINPUT_EVENT_TABLET_PAD_RING() -> {
                val position = Input.libinput_event_tablet_pad_get_ring_position(e)
                val finger = Input.libinput_event_tablet_pad_get_ring_source(e) == Input.LIBINPUT_TABLET_PAD_RING_SOURCE_FINGER()
                val ring = Input.libinput_event_tablet_pad_get_ring_number(e)
                toMain { onPadRing(time, pad, ring, position.takeIf { it >= 0 }, finger) }
            }
            Input.LIBINPUT_EVENT_TABLET_PAD_STRIP() -> {
                val position = Input.libinput_event_tablet_pad_get_strip_position(e)
                val finger = Input.libinput_event_tablet_pad_get_strip_source(e) == Input.LIBINPUT_TABLET_PAD_STRIP_SOURCE_FINGER()
                val strip = Input.libinput_event_tablet_pad_get_strip_number(e)
                toMain { onPadStrip(time, pad, strip, position.takeIf { it >= 0 }, finger) }
            }
        }
    }

    // A tablet or a pad came: what it is (its size, ids, buttons and groups).
    private fun tabletOf(device: MemorySegment, name: String): Tablet = Arena.ofConfined().use { temp ->
        val width = temp.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE)
        val height = temp.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE)
        val sized = Input.libinput_device_get_size(device, width, height) == 0
        Tablet(
            name, Input.libinput_device_get_id_vendor(device), Input.libinput_device_get_id_product(device), pathOf(device),
            if (sized) width.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) else 0.0,
            if (sized) height.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) else 0.0,
            screen = Input.libinput_device_config_calibration_has_matrix(device) != 0,
        )
    }

    private fun padOf(device: MemorySegment, name: String): TabletPad {
        val buttons = Input.libinput_device_tablet_pad_get_num_buttons(device).coerceAtLeast(0)
        val rings = Input.libinput_device_tablet_pad_get_num_rings(device).coerceAtLeast(0)
        val strips = Input.libinput_device_tablet_pad_get_num_strips(device).coerceAtLeast(0)
        val groups = (0 until Input.libinput_device_tablet_pad_get_num_mode_groups(device).coerceAtLeast(0)).mapNotNull { i ->
            val g = Input.libinput_device_tablet_pad_get_mode_group(device, i)
            if (g == MemorySegment.NULL) return@mapNotNull null
            TabletPad.Group(
                (0 until buttons).filter { Input.libinput_tablet_pad_mode_group_has_button(g, it) != 0 },
                (0 until rings).filter { Input.libinput_tablet_pad_mode_group_has_ring(g, it) != 0 },
                (0 until strips).filter { Input.libinput_tablet_pad_mode_group_has_strip(g, it) != 0 },
                Input.libinput_tablet_pad_mode_group_get_num_modes(g).coerceAtLeast(1),
            )
        }
        return if (groups.isEmpty()) TabletPad(name, buttons, rings, strips, path = pathOf(device)) else TabletPad(name, buttons, rings, strips, groups, path = pathOf(device))
    }

    // The device's file ("/dev/input/event7"), or null.
    private fun pathOf(device: MemorySegment): String? {
        val udevDevice = Input.libinput_device_get_udev_device(device)
        if (udevDevice == MemorySegment.NULL) return null
        return try {
            CStrings.borrowed(Input.udev_device_get_devnode(udevDevice))
        } finally {
            Input.udev_device_unref(udevDevice)
        }
    }

    // A tablet's pen and its pad are one device group: each knows the other once both are there.
    private fun pairPads() {
        val tablets = devices.values.filter { it.tablet != null }
        for (padDevice in devices.values) {
            val pad = padDevice.pad ?: continue
            if (pad.tablet != null) continue
            val group = groups[padDevice.handle.address()] ?: continue
            pad.tablet = tablets.firstOrNull { groups[it.handle.address()] == group }?.tablet
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
        toMain { onPointerAxis(time, scroll) }
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
        toMain { onGesture(gesture) }
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
            if (has(Input.LIBINPUT_DEVICE_CAP_TABLET_PAD())) add(InputDevice.Kind.TABLET_PAD)
            if (has(Input.LIBINPUT_DEVICE_CAP_SWITCH())) add(InputDevice.Kind.SWITCH)
        }
        val known = InputDevice(name, kinds, device)
        if (InputDevice.Kind.TABLET in kinds) known.tablet = tabletOf(device, name)
        if (InputDevice.Kind.TABLET_PAD in kinds) known.pad = padOf(device, name)
        devices[device.address()] = known
        groups[device.address()] = Input.libinput_device_get_device_group(device).address()
        log.info { "Device: $name (${kinds.joinToString { it.name.lowercase() }})" }
        configure(known)
        pairPads()
        published = devices.values.toList()
        known.tablet?.let { t -> toMain { onTabletAdded(t) } }
        known.pad?.let { p -> toMain { onPadAdded(p) } }
        toMain { onDevicesChanged() }
    }

    // Applies the settings that are for [device] (pointers, touchpads, tablets).
    private fun configure(device: InputDevice) {
        val d = device.handle
        if (device.tablet != null || device.pad != null) {
            val s = settings.forTablet(device.name)
            device.tablet?.settings = s
            Input.libinput_device_config_send_events_set_mode(
                d, if (s.enabled == false) Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED() else Input.LIBINPUT_CONFIG_SEND_EVENTS_ENABLED(),
            )
            if (InputDevice.Kind.POINTER !in device.kinds) return
        }
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
        val time = now()
        // Fingers on a touch screen that went (or whose terminal we left): programs forget them.
        if (touching.remove(device)?.isNotEmpty() == true) {
            log.info("A touch screen went away with fingers on it: the touches are cancelled")
            toMain { onTouchCancel(time) }
        }
        // Tools near the tablet, and pad buttons held.
        for ((tool, n) in near.toList()) if (n.device == device) away(time, tool)
        devices[device]?.pad?.let { pad -> padHeld.remove(pad)?.forEach { button -> toMain { onPadButton(time, pad, button, false) } } }
        val keys = held.remove(device) ?: return
        for (code in keys) {
            // Pointer buttons are 0x110 (BTN_LEFT) and above; keys are below.
            if (code >= BTN_MOUSE) toMain { onPointerButton(time, code, false) } else toMain { onKey(time, code, false) }
        }
    }

    private fun releaseEverything() {
        for (device in (held.keys + near.values.map { it.device } + touching.keys + devices.keys).toSet()) releaseHeld(device)
    }

    private fun now(): Int = (System.nanoTime() / 1_000_000).toInt()

    override fun close() {
        stopThread()
        loop.remove(fd)
        Input.libinput_unref(context)
        Input.udev_unref(udev)
        arena.close()
    }

    private companion object {
        const val ABSOLUTE_RANGE = 1_000_000
        const val OPEN_WAIT_SECONDS = 5L
        const val PAUSE_WAIT_MILLIS = 2000L
        const val BTN_MOUSE = 0x110
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
