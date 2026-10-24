// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.pointergesturesunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class ZwpPointerGestureSwipeV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPointerGestureSwipeV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Multi-finger swipe begin
         *
         * This event is sent when a multi-finger swipe gesture is detected
         * on the device.
         */
        public fun begin(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) {}

        /**
         * Multi-finger swipe motion
         *
         * This event is sent when a multi-finger swipe gesture changes the
         * position of the logical center.
         *
         * The dx and dy coordinates are relative coordinates of the logical
         * center of the gesture compared to the previous event.
         */
        public fun update(proxy: ZwpPointerGestureSwipeV1, time: Int, dx: Double, dy: Double) {}

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
        public fun end(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, cancelled: Int) {}
    }

    /**
     * Destroy the pointer swipe gesture object
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
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
                if (Wire.tracing) Wire.received(this, "begin", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, surface=${Wire.show(surface)}, fingers=${Wire.show(fingers)}")
                handler?.begin(this, serial, time, surface, fingers)
            }
            1 -> {
                val time = args.uint()
                val dx = args.fixed()
                val dy = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "update", "time=${Wire.show(time)}, dx=${Wire.show(dx)}, dy=${Wire.show(dy)}")
                handler?.update(this, time, dx, dy)
            }
            2 -> {
                val serial = args.uint()
                val time = args.uint()
                val cancelled = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "end", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, cancelled=${Wire.show(cancelled)}")
                handler?.end(this, serial, time, cancelled)
            }
        }
    }

    public companion object : ProxyType<ZwpPointerGestureSwipeV1>("zwp_pointer_gesture_swipe_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPointerGestureSwipeV1 = ZwpPointerGestureSwipeV1(connection, id, version)
    }
}
