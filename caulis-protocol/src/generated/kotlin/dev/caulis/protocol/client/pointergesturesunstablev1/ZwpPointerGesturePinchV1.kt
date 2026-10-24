// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.pointergesturesunstablev1

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class ZwpPointerGesturePinchV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPointerGesturePinchV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Multi-finger pinch begin
         *
         * This event is sent when a multi-finger pinch gesture is detected
         * on the device.
         */
        public fun begin(proxy: ZwpPointerGesturePinchV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) {}

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
        public fun update(proxy: ZwpPointerGesturePinchV1, time: Int, dx: Double, dy: Double, scale: Double, rotation: Double) {}

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
        public fun end(proxy: ZwpPointerGesturePinchV1, serial: Int, time: Int, cancelled: Int) {}
    }

    /**
     * Destroy the pinch gesture object
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val serial = args.uint()
                val time = args.uint()
                val surface = args.obj(WlSurface::class.java)
                val fingers = args.uint()
                args.end()
                handler?.begin(this, serial, time, surface, fingers)
            }
            1 -> {
                val time = args.uint()
                val dx = args.fixed()
                val dy = args.fixed()
                val scale = args.fixed()
                val rotation = args.fixed()
                args.end()
                handler?.update(this, time, dx, dy, scale, rotation)
            }
            2 -> {
                val serial = args.uint()
                val time = args.uint()
                val cancelled = args.int()
                args.end()
                handler?.end(this, serial, time, cancelled)
            }
        }
    }

    public companion object : ProxyType<ZwpPointerGesturePinchV1>("zwp_pointer_gesture_pinch_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPointerGesturePinchV1 = ZwpPointerGesturePinchV1(connection, id, version)
    }
}
