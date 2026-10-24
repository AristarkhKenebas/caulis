// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointergesturesunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * A swipe gesture object
 *
 * A swipe gesture object notifies a client about a multi-finger swipe
 * gesture detected on an indirect input device such as a touchpad.
 * The gesture is usually initiated by multiple fingers moving in the
 * same direction but once initiated the direction may change.
 * The precise conditions of when such a gesture is detected are
 * implementation-dependent.
 *
 * A gesture consists of three stages: begin, update (optional) and end.
 * There cannot be multiple simultaneous hold, pinch or swipe gestures on a
 * same pointer/seat, how compositors prevent these situations is
 * implementation-dependent.
 *
 * A gesture may be cancelled by the compositor or the hardware.
 * Clients should not consider performing permanent or irreversible
 * actions until the end of a gesture has been received.
 */
public class ZwpPointerGestureSwipeV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPointerGestureSwipeV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_pointer_gesture_swipe_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the pointer swipe gesture object
         */
        public fun destroy(resource: ZwpPointerGestureSwipeV1)
    }

    /**
     * Multi-finger swipe begin
     *
     * This event is sent when a multi-finger swipe gesture is detected
     * on the device.
     */
    public fun sendBegin(serial: Int, time: Int, surface: WlSurface, fingers: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "begin", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, surface=${Wire.show(surface)}, fingers=${Wire.show(fingers)}")
        event(0).uint(serial).uint(time).obj(surface).uint(fingers).end()
    }

    /**
     * Multi-finger swipe motion
     *
     * This event is sent when a multi-finger swipe gesture changes the
     * position of the logical center.
     *
     * The dx and dy coordinates are relative coordinates of the logical
     * center of the gesture compared to the previous event.
     */
    public fun sendUpdate(time: Int, dx: Double, dy: Double) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "update", "time=${Wire.show(time)}, dx=${Wire.show(dx)}, dy=${Wire.show(dy)}")
        event(1).uint(time).fixed(dx).fixed(dy).end()
    }

    /**
     * Multi-finger swipe end
     *
     * This event is sent when a multi-finger swipe gesture ceases to
     * be valid. This may happen when one or more fingers are lifted or
     * the gesture is cancelled.
     *
     * When a gesture is cancelled, the client should undo state changes
     * caused by this gesture. What causes a gesture to be cancelled is
     * implementation-dependent.
     */
    public fun sendEnd(serial: Int, time: Int, cancelled: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "end", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, cancelled=${Wire.show(cancelled)}")
        event(2).uint(serial).uint(time).int(cancelled).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_pointer_gesture_swipe_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_pointer_gesture_swipe_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPointerGestureSwipeV1>("zwp_pointer_gesture_swipe_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPointerGestureSwipeV1 = ZwpPointerGestureSwipeV1(client, id, version)
    }
}
