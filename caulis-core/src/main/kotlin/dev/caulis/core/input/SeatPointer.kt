package dev.caulis.core.input

import dev.caulis.core.Surface
import dev.caulis.protocol.Client
import dev.caulis.protocol.wayland.WlPointer

/**
 * The pointer's side of the seat: motion, buttons and the wheel told to programs, drag and drop.
 */
internal class SeatPointer(private val seat: Seat) {

    fun pointerFrame() {
        val focus = seat.pointerFocus ?: return
        pointersOf(focus.resource.client).forEach { it.sendFrame() }
    }

    fun pointerMotion(timeMillis: Int, under: Surface?, unitX: Double, unitY: Double) {
        val surface = under?.takeIf { it.resource.isAlive && seat.allowed(it) }
        // Surfaces in screen pixels (Xwayland's) get the position in their pixels.
        val pixels = surface?.nativeScale ?: 1.0
        val x = unitX * pixels
        val y = unitY * pixels
        // Dragging: the surface under the pointer hears of the drag, not of the pointer.
        drag?.let { current ->
            current.motion(timeMillis, surface, x, y)
            return
        }
        val old = seat.pointerFocus
        if (old !== surface) {
            focusLog.trace { "Pointer: ${old?.roleObject ?: old} → ${surface?.roleObject ?: surface}" }
            if (old != null && old.resource.isAlive) {
                val serial = seat.display.nextSerial()
                pointersOf(old.resource.client).forEach {
                    it.sendLeave(serial, old.resource)
                    it.sendFrame()
                }
            }
            seat.pointerFocus = surface
            seat.cursorSurface = null
            seat.cursorShape = "default"
            discreteRest.fill(0)
            if (surface != null) {
                val serial = seat.display.nextSerial()
                pointersOf(surface.resource.client).forEach {
                    it.sendEnter(serial, surface.resource, x, y)
                    it.sendFrame()
                }
                seat.watchForDestruction(surface)
            }
            return
        }
        if (surface == null) return
        pointersOf(surface.resource.client).forEach {
            it.sendMotion(timeMillis, x, y)
            it.sendFrame()
        }
    }

    internal var drag: Seat.Drag? = null
        private set

    // Mouse buttons programs were told are held now.
    private val buttonsDown = HashSet<Int>()

    // True while a button is held (a drag may only start then).
    internal val buttonHeld: Boolean get() = buttonsDown.isNotEmpty()

    // [started] takes over the pointer: the program it was over hears that it left.
    internal fun startDrag(started: Seat.Drag) {
        drag?.let { old ->
            drag = null
            old.cancel()
        }
        pointerMotion(0, null, 0.0, 0.0)
        drag = started
        seat.onCursorChange()
    }

    // Ends the drag going on (the screen locks, its program went away): nothing is dropped.
    internal fun cancelDrag() {
        val current = drag ?: return
        drag = null
        current.cancel()
        seat.onCursorChange()
    }

    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed) buttonsDown += button else buttonsDown -= button
        drag?.let { current ->
            // Letting go drops what is dragged where the pointer is.
            if (!pressed && buttonsDown.isEmpty()) {
                drag = null
                current.drop()
                seat.onCursorChange()
            }
            return
        }
        val focus = seat.pointerFocus?.takeIf(seat::allowed) ?: return
        val serial = seat.display.nextSerial()
        val state = if (pressed) WlPointer.ButtonState.PRESSED else WlPointer.ButtonState.RELEASED
        pointersOf(focus.resource.client).forEach {
            it.sendButton(serial, timeMillis, button, state)
            it.sendFrame()
        }
        seat.inputSent(focus.resource.client, serial)
    }

    // Parts of a wheel notch not yet sent as whole steps to programs that only know whole
    // ones (wl_pointer before version 8): horizontal, vertical.
    private val discreteRest = IntArray(2)

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        val source = if (value120 != 0) dev.caulis.core.input.Scroll.Source.WHEEL else dev.caulis.core.input.Scroll.Source.FINGER
        pointerAxis(
            timeMillis,
            if (vertical) dev.caulis.core.input.Scroll(source, vertical = value, vertical120 = value120)
            else dev.caulis.core.input.Scroll(source, horizontal = value, horizontal120 = value120),
        )
    }

    fun pointerAxis(timeMillis: Int, scroll: Scroll) {
        val focus = seat.pointerFocus?.takeIf(seat::allowed) ?: return
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

    private fun pointersOf(client: Client) = seat.pointers.filter { it.client === client }
}
