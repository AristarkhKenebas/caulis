// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointergesturesunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * A pinch gesture object
 *
 * A pinch gesture object notifies a client about a multi-finger pinch
 * gesture detected on an indirect input device such as a touchpad.
 * The gesture is usually initiated by multiple fingers moving towards
 * each other or away from each other, or by two or more fingers rotating
 * around a logical center of gravity. The precise conditions of when
 * such a gesture is detected are implementation-dependent.
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
public class ZwpPointerGesturePinchV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPointerGesturePinchV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_pointer_gesture_pinch_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the pinch gesture object
         */
        public fun destroy(resource: ZwpPointerGesturePinchV1)
    }

    /**
     * Multi-finger pinch begin
     *
     * This event is sent when a multi-finger pinch gesture is detected
     * on the device.
     */
    public fun sendBegin(serial: Int, time: Int, surface: WlSurface, fingers: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).uint(time).obj(surface).uint(fingers).end()
    }

    /**
     * Multi-finger pinch motion
     *
     * This event is sent when a multi-finger pinch gesture changes the
     * position of the logical center, the rotation or the relative scale.
     *
     * The dx and dy coordinates are relative coordinates in the
     * surface coordinate space of the logical center of the gesture.
     *
     * The scale factor is an absolute scale compared to the
     * pointer_gesture_pinch.begin event, e.g. a scale of 2 means the fingers
     * are now twice as far apart as on pointer_gesture_pinch.begin.
     *
     * The rotation is the relative angle in degrees clockwise compared to the previous
     * pointer_gesture_pinch.begin or pointer_gesture_pinch.update event.
     */
    public fun sendUpdate(time: Int, dx: Double, dy: Double, scale: Double, rotation: Double) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(time).fixed(dx).fixed(dy).fixed(scale).fixed(rotation).end()
    }

    /**
     * Multi-finger pinch end
     *
     * This event is sent when a multi-finger pinch gesture ceases to
     * be valid. This may happen when one or more fingers are lifted or
     * the gesture is cancelled.
     *
     * When a gesture is cancelled, the client should undo state changes
     * caused by this gesture. What causes a gesture to be cancelled is
     * implementation-dependent.
     */
    public fun sendEnd(serial: Int, time: Int, cancelled: Int) {
        if (!isAlive || !client.isConnected) return
        event(2).uint(serial).uint(time).int(cancelled).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_pointer_gesture_pinch_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_pointer_gesture_pinch_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPointerGesturePinchV1>("zwp_pointer_gesture_pinch_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPointerGesturePinchV1 = ZwpPointerGesturePinchV1(client, id, version)
    }
}
