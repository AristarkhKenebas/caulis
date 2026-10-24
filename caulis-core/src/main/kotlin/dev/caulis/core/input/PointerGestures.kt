package dev.caulis.core.input

import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.Resource
import dev.caulis.protocol.pointergesturesunstablev1.ZwpPointerGestureHoldV1
import dev.caulis.protocol.pointergesturesunstablev1.ZwpPointerGesturePinchV1
import dev.caulis.protocol.pointergesturesunstablev1.ZwpPointerGestureSwipeV1
import dev.caulis.protocol.pointergesturesunstablev1.ZwpPointerGesturesV1
import dev.caulis.protocol.wayland.WlPointer
import dev.caulis.protocol.wayland.WlSurface

/**
 * zwp_pointer_gestures_v1: touchpad gestures for programs (pinch to zoom in a browser or a
 * photo viewer, swipe back and forth). The compositor keeps the gestures it uses itself
 * and passes the others here with [send]; they go to the program under the pointer.
 */
class PointerGestures(private val display: Display, private val seat: Seat) {

    private val swipes = ArrayList<ZwpPointerGestureSwipeV1>()
    private val pinches = ArrayList<ZwpPointerGesturePinchV1>()
    private val holds = ArrayList<ZwpPointerGestureHoldV1>()

    // The program (and its surface) that got the current gesture's begin gets the rest of it too.
    private var target: Client? = null
    private var targetSurface: WlSurface? = null

    init {
        display.addGlobal(ZwpPointerGesturesV1, VERSION) { gestures ->
            gestures.requests = object : ZwpPointerGesturesV1.Requests {
                override fun getSwipeGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGestureSwipeV1, pointer: WlPointer) =
                    keep(swipes, id) { id.requests = object : ZwpPointerGestureSwipeV1.Requests { override fun destroy(resource: ZwpPointerGestureSwipeV1) {} } }

                override fun getPinchGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGesturePinchV1, pointer: WlPointer) =
                    keep(pinches, id) { id.requests = object : ZwpPointerGesturePinchV1.Requests { override fun destroy(resource: ZwpPointerGesturePinchV1) {} } }

                override fun getHoldGesture(resource: ZwpPointerGesturesV1, id: ZwpPointerGestureHoldV1, pointer: WlPointer) =
                    keep(holds, id) { id.requests = object : ZwpPointerGestureHoldV1.Requests { override fun destroy(resource: ZwpPointerGestureHoldV1) {} } }

                override fun release(resource: ZwpPointerGesturesV1) {}
            }
        }
    }

    private fun <T : Resource> keep(list: MutableList<T>, resource: T, setup: () -> Unit) {
        setup()
        list += resource
        resource.onDestroy { list -= resource }
    }

    /** Passes [gesture] to the program under the pointer (if it listens for gestures). */
    fun send(gesture: Gesture) {
        if (gesture is Gesture.SwipeBegin || gesture is Gesture.PinchBegin || gesture is Gesture.HoldBegin) {
            val under = seat.pointerFocus?.resource
            target = under?.client
            targetSurface = under
        }
        val client = target ?: return
        val surface = targetSurface?.takeIf { it.isAlive } ?: return
        val serial = display.nextSerial()
        when (gesture) {
            is Gesture.SwipeBegin -> swipes.of(client).forEach { it.sendBegin(serial, gesture.timeMillis, surface, gesture.fingers) }
            is Gesture.SwipeUpdate -> swipes.of(client).forEach { it.sendUpdate(gesture.timeMillis, gesture.dx, gesture.dy) }
            is Gesture.SwipeEnd -> swipes.of(client).forEach { it.sendEnd(serial, gesture.timeMillis, cancelled(gesture.cancelled)) }
            is Gesture.PinchBegin -> pinches.of(client).forEach { it.sendBegin(serial, gesture.timeMillis, surface, gesture.fingers) }
            is Gesture.PinchUpdate -> pinches.of(client).forEach { it.sendUpdate(gesture.timeMillis, gesture.dx, gesture.dy, gesture.scale, gesture.rotation) }
            is Gesture.PinchEnd -> pinches.of(client).forEach { it.sendEnd(serial, gesture.timeMillis, cancelled(gesture.cancelled)) }
            is Gesture.HoldBegin -> holds.of(client).forEach { it.sendBegin(serial, gesture.timeMillis, surface, gesture.fingers) }
            is Gesture.HoldEnd -> holds.of(client).forEach { it.sendEnd(serial, gesture.timeMillis, cancelled(gesture.cancelled)) }
        }
        if (gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd || gesture is Gesture.HoldEnd) {
            target = null
            targetSurface = null
        }
    }

    private fun <T : Resource> List<T>.of(client: Client) = filter { it.client === client && it.isAlive }

    private fun cancelled(value: Boolean) = if (value) 1 else 0

    companion object {
        const val VERSION = 3
    }
}
