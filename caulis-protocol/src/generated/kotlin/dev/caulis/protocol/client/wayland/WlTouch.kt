// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Touchscreen input device
 *
 * The wl_touch interface represents a touchscreen
 * associated with a seat.
 *
 * Touch interactions can consist of one or more contacts.
 * For each contact, a series of events is generated, starting
 * with a down event, followed by zero or more motion events,
 * and ending with an up event. Events relating to the same
 * contact point can be identified by the ID of the sequence.
 */
public class WlTouch private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlTouch> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Touch down event and beginning of a touch sequence
         *
         * A new touch point has appeared on the surface. This touch point is
         * assigned a unique ID. Future events from this touch point reference
         * this ID. The ID ceases to be valid after a touch up event and may be
         * reused in the future.
         */
        public fun down(proxy: WlTouch, serial: Int, time: Int, surface: WlSurface, id: Int, x: Double, y: Double) {}

        /**
         * End of a touch event sequence
         *
         * The touch point has disappeared. No further events will be sent for
         * this touch point and the touch point's ID is released and may be
         * reused in a future touch down event.
         */
        public fun up(proxy: WlTouch, serial: Int, time: Int, id: Int) {}

        /**
         * Update of touch point coordinates
         *
         * A touch point has changed coordinates.
         */
        public fun motion(proxy: WlTouch, time: Int, id: Int, x: Double, y: Double) {}

        /**
         * End of touch frame event
         *
         * Indicates the end of a set of events that logically belong together.
         * A client is expected to accumulate the data in all events within the
         * frame before proceeding.
         *
         * A wl_touch.frame terminates at least one event but otherwise no
         * guarantee is provided about the set of events within a frame. A client
         * must assume that any state not updated in a frame is unchanged from the
         * previously known state.
         */
        public fun frame(proxy: WlTouch) {}

        /**
         * Touch session cancelled
         *
         * Sent if the compositor decides the touch stream is a global
         * gesture. No further events are sent to the clients from that
         * particular gesture. Touch cancellation applies to all touch points
         * currently active on this client's surface. The client is
         * responsible for finalizing the touch points, future touch points on
         * this surface may reuse the touch point ID.
         *
         * No frame event is required after the cancel event.
         */
        public fun cancel(proxy: WlTouch) {}

        /**
         * Update shape of touch point
         *
         * Sent when a touchpoint has changed its shape.
         *
         * This event does not occur on its own. It is sent before a
         * wl_touch.frame event and carries the new shape information for
         * any previously reported, or new touch points of that frame.
         *
         * Other events describing the touch point such as wl_touch.down,
         * wl_touch.motion or wl_touch.orientation may be sent within the
         * same wl_touch.frame. A client should treat these events as a single
         * logical touch point update. The order of wl_touch.shape,
         * wl_touch.orientation and wl_touch.motion is not guaranteed.
         * A wl_touch.down event is guaranteed to occur before the first
         * wl_touch.shape event for this touch ID but both events may occur within
         * the same wl_touch.frame.
         *
         * A touchpoint shape is approximated by an ellipse through the major and
         * minor axis length. The major axis length describes the longer diameter
         * of the ellipse, while the minor axis length describes the shorter
         * diameter. Major and minor are orthogonal and both are specified in
         * surface-local coordinates. The center of the ellipse is always at the
         * touchpoint location as reported by wl_touch.down or wl_touch.motion.
         *
         * This event is only sent by the compositor if the touch device supports
         * shape reports. The client has to make reasonable assumptions about the
         * shape if it did not receive this event.
         *
         * Since version 6.
         */
        public fun shape(proxy: WlTouch, id: Int, major: Double, minor: Double) {}

        /**
         * Update orientation of touch point
         *
         * Sent when a touchpoint has changed its orientation.
         *
         * This event does not occur on its own. It is sent before a
         * wl_touch.frame event and carries the new shape information for
         * any previously reported, or new touch points of that frame.
         *
         * Other events describing the touch point such as wl_touch.down,
         * wl_touch.motion or wl_touch.shape may be sent within the
         * same wl_touch.frame. A client should treat these events as a single
         * logical touch point update. The order of wl_touch.shape,
         * wl_touch.orientation and wl_touch.motion is not guaranteed.
         * A wl_touch.down event is guaranteed to occur before the first
         * wl_touch.orientation event for this touch ID but both events may occur
         * within the same wl_touch.frame.
         *
         * The orientation describes the clockwise angle of a touchpoint's major
         * axis to the positive surface y-axis and is normalized to the -180 to
         * +180 degree range. The granularity of orientation depends on the touch
         * device, some devices only support binary rotation values between 0 and
         * 90 degrees.
         *
         * This event is only sent by the compositor if the touch device supports
         * orientation reports.
         *
         * Since version 6.
         */
        public fun orientation(proxy: WlTouch, id: Int, orientation: Double) {}
    }

    /**
     * Release the touch object
     *
     * Since version 3.
     */
    public fun release(): Unit {
        check(version >= 3) { "wl_touch.release needs version 3, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
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
                val id = args.int()
                val x = args.fixed()
                val y = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "down", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, surface=${Wire.show(surface)}, id=${Wire.show(id)}, x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.down(this, serial, time, surface, id, x, y)
            }
            1 -> {
                val serial = args.uint()
                val time = args.uint()
                val id = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "up", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, id=${Wire.show(id)}")
                handler?.up(this, serial, time, id)
            }
            2 -> {
                val time = args.uint()
                val id = args.int()
                val x = args.fixed()
                val y = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "motion", "time=${Wire.show(time)}, id=${Wire.show(id)}, x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.motion(this, time, id, x, y)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "frame", "")
                handler?.frame(this)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "cancel", "")
                handler?.cancel(this)
            }
            5 -> {
                val id = args.int()
                val major = args.fixed()
                val minor = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "shape", "id=${Wire.show(id)}, major=${Wire.show(major)}, minor=${Wire.show(minor)}")
                handler?.shape(this, id, major, minor)
            }
            6 -> {
                val id = args.int()
                val orientation = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "orientation", "id=${Wire.show(id)}, orientation=${Wire.show(orientation)}")
                handler?.orientation(this, id, orientation)
            }
        }
    }

    public companion object : ProxyType<WlTouch>("wl_touch", 11) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlTouch = WlTouch(connection, id, version)
    }
}
