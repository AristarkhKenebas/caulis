// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

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
public class WlTouch private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlTouch> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_touch. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Release the touch object
         *
         * Since version 3.
         */
        public fun release(resource: WlTouch)
    }

    /**
     * Touch down event and beginning of a touch sequence
     *
     * A new touch point has appeared on the surface. This touch point is
     * assigned a unique ID. Future events from this touch point reference
     * this ID. The ID ceases to be valid after a touch up event and may be
     * reused in the future.
     */
    public fun sendDown(serial: Int, time: Int, surface: WlSurface, id: Int, x: Double, y: Double) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).uint(time).obj(surface).int(id).fixed(x).fixed(y).end()
    }

    /**
     * End of a touch event sequence
     *
     * The touch point has disappeared. No further events will be sent for
     * this touch point and the touch point's ID is released and may be
     * reused in a future touch down event.
     */
    public fun sendUp(serial: Int, time: Int, id: Int) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(serial).uint(time).int(id).end()
    }

    /**
     * Update of touch point coordinates
     *
     * A touch point has changed coordinates.
     */
    public fun sendMotion(time: Int, id: Int, x: Double, y: Double) {
        if (!isAlive || !client.isConnected) return
        event(2).uint(time).int(id).fixed(x).fixed(y).end()
    }

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
    public fun sendFrame() {
        if (!isAlive || !client.isConnected) return
        event(3).end()
    }

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
    public fun sendCancel() {
        if (!isAlive || !client.isConnected) return
        event(4).end()
    }

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
    public fun sendShape(id: Int, major: Double, minor: Double) {
        if (!isAlive || !client.isConnected) return
        if (version < 6) return // the client is too old for this event
        event(5).int(id).fixed(major).fixed(minor).end()
    }

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
    public fun sendOrientation(id: Int, orientation: Double) {
        if (!isAlive || !client.isConnected) return
        if (version < 6) return // the client is too old for this event
        event(6).int(id).fixed(orientation).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_touch is not implemented")
        when (opcode) {
            0 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_touch.release needs version 3")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_touch request $opcode")
        }
    }

    public companion object : ResourceType<WlTouch>("wl_touch", 11) {
        internal override fun create(client: Client, id: Int, version: Int): WlTouch = WlTouch(client, id, version)
    }
}
