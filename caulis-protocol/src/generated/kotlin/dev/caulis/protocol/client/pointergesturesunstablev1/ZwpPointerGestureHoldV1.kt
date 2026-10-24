// Generated from pointer-gestures-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.pointergesturesunstablev1

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * A hold gesture object
 *
 * A hold gesture object notifies a client about a single- or
 * multi-finger hold gesture detected on an indirect input device such as
 * a touchpad. The gesture is usually initiated by one or more fingers
 * being held down without significant movement. The precise conditions
 * of when such a gesture is detected are implementation-dependent.
 *
 * In particular, this gesture may be used to cancel kinetic scrolling.
 *
 * A hold gesture consists of two stages: begin and end. Unlike pinch and
 * swipe there is no update stage.
 * There cannot be multiple simultaneous hold, pinch or swipe gestures on a
 * same pointer/seat, how compositors prevent these situations is
 * implementation-dependent.
 *
 * A gesture may be cancelled by the compositor or the hardware.
 * Clients should not consider performing permanent or irreversible
 * actions until the end of a gesture has been received.
 */
public class ZwpPointerGestureHoldV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPointerGestureHoldV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Multi-finger hold begin
         *
         * This event is sent when a hold gesture is detected on the device.
         *
         * Since version 3.
         */
        public fun begin(proxy: ZwpPointerGestureHoldV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) {}

        /**
         * Multi-finger hold end
         *
         * This event is sent when a hold gesture ceases to
         * be valid. This may happen when the holding fingers are lifted or
         * the gesture is cancelled, for example if the fingers move past an
         * implementation-defined threshold, the finger count changes or the hold
         * gesture changes into a different type of gesture.
         *
         * When a gesture is cancelled, the client may need to undo state changes
         * caused by this gesture. What causes a gesture to be cancelled is
         * implementation-dependent.
         *
         * Since version 3.
         */
        public fun end(proxy: ZwpPointerGestureHoldV1, serial: Int, time: Int, cancelled: Int) {}
    }

    /**
     * Destroy the hold gesture object
     *
     * Since version 3.
     */
    public fun destroy(): Unit {
        check(version >= 3) { "zwp_pointer_gesture_hold_v1.destroy needs version 3, this object has $version" }
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
                val serial = args.uint()
                val time = args.uint()
                val cancelled = args.int()
                args.end()
                handler?.end(this, serial, time, cancelled)
            }
        }
    }

    public companion object : ProxyType<ZwpPointerGestureHoldV1>("zwp_pointer_gesture_hold_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPointerGestureHoldV1 = ZwpPointerGestureHoldV1(connection, id, version)
    }
}
