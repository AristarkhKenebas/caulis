// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Pointer input device
 *
 * The wl_pointer interface represents one or more input devices,
 * such as mice, which control the pointer location and pointer_focus
 * of a seat.
 *
 * The wl_pointer interface generates motion, enter and leave
 * events for the surfaces that the pointer is located over,
 * and button and axis events for button presses, button releases
 * and scrolling.
 */
public class WlPointer private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlPointer> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_pointer. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set the pointer surface
         *
         * Set the pointer surface, i.e., the surface that contains the
         * pointer image (cursor). This request gives the surface the role
         * of a cursor. If the surface already has another role, it raises
         * a protocol error.
         *
         * The cursor actually changes only if the pointer
         * focus for this device is one of the requesting client's surfaces
         * or the surface parameter is the current pointer surface. If
         * there was a previous surface set with this request it is
         * replaced. If surface is NULL, the pointer image is hidden.
         *
         * The parameters hotspot_x and hotspot_y define the position of
         * the pointer surface relative to the pointer location. Its
         * top-left corner is always at (x, y) - (hotspot_x, hotspot_y),
         * where (x, y) are the coordinates of the pointer location, in
         * surface-local coordinates.
         *
         * On wl_surface.offset requests to the pointer surface, hotspot_x
         * and hotspot_y are decremented by the x and y parameters
         * passed to the request. The offset must be applied by
         * wl_surface.commit as usual.
         *
         * The hotspot can also be updated by passing the currently set
         * pointer surface to this request with new values for hotspot_x
         * and hotspot_y.
         *
         * The input region is ignored for wl_surfaces with the role of
         * a cursor. When the use as a cursor ends, the wl_surface is
         * unmapped.
         *
         * The serial parameter must match the latest wl_pointer.enter
         * serial number sent to the client. Otherwise the request will be
         * ignored.
         */
        public fun setCursor(resource: WlPointer, serial: Int, surface: WlSurface?, hotspotX: Int, hotspotY: Int)

        /**
         * Release the pointer object
         *
         * Using this request a client can tell the server that it is not going to
         * use the pointer object anymore.
         *
         * This request destroys the pointer proxy object, so clients must not call
         * wl_pointer_destroy() after using this request.
         *
         * Since version 3.
         */
        public fun release(resource: WlPointer)
    }

    /**
     * Enter event
     *
     * Notification that this seat's pointer is focused on a certain
     * surface.
     *
     * When a seat's focus enters a surface, the pointer image
     * is undefined and a client should respond to this event by setting
     * an appropriate pointer image with the set_cursor request.
     */
    public fun sendEnter(serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).obj(surface).fixed(surfaceX).fixed(surfaceY).end()
    }

    /**
     * Leave event
     *
     * Notification that this seat's pointer is no longer focused on
     * a certain surface.
     *
     * The leave notification is sent before the enter notification
     * for the new focus.
     */
    public fun sendLeave(serial: Int, surface: WlSurface) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(serial).obj(surface).end()
    }

    /**
     * Pointer motion event
     *
     * Notification of pointer location change. The arguments
     * surface_x and surface_y are the location relative to the
     * focused surface.
     */
    public fun sendMotion(time: Int, surfaceX: Double, surfaceY: Double) {
        if (!isAlive || !client.isConnected) return
        event(2).uint(time).fixed(surfaceX).fixed(surfaceY).end()
    }

    /**
     * Pointer button event
     *
     * Mouse button click and release notifications.
     *
     * The location of the click is given by the last motion, warp or
     * enter event.
     * The time argument is a timestamp with millisecond
     * granularity, with an undefined base.
     *
     * The button is a button code as defined in the Linux kernel's
     * linux/input-event-codes.h header file, e.g. BTN_LEFT.
     *
     * Any 16-bit button code value is reserved for future additions to the
     * kernel's event code list. All other button codes above 0xFFFF are
     * currently undefined but may be used in future versions of this
     * protocol.
     */
    public fun sendButton(serial: Int, time: Int, button: Int, state: Int) {
        if (!isAlive || !client.isConnected) return
        event(3).uint(serial).uint(time).uint(button).uint(state).end()
    }

    /**
     * Axis event
     *
     * Scroll and other axis notifications.
     *
     * For scroll events (vertical and horizontal scroll axes), the
     * value parameter is the length of a vector along the specified
     * axis in a coordinate space identical to those of motion events,
     * representing a relative movement along the specified axis.
     *
     * For devices that support movements non-parallel to axes multiple
     * axis events will be emitted.
     *
     * When applicable, for example for touch pads, the server can
     * choose to emit scroll events where the motion vector is
     * equivalent to a motion event vector.
     *
     * When applicable, a client can transform its content relative to the
     * scroll distance.
     */
    public fun sendAxis(time: Int, axis: Int, value: Double) {
        if (!isAlive || !client.isConnected) return
        event(4).uint(time).uint(axis).fixed(value).end()
    }

    /**
     * End of a pointer event sequence
     *
     * Indicates the end of a set of events that logically belong together.
     * A client is expected to accumulate the data in all events within the
     * frame before proceeding.
     *
     * All wl_pointer events before a wl_pointer.frame event belong
     * logically together. For example, in a diagonal scroll motion the
     * compositor will send an optional wl_pointer.axis_source event, two
     * wl_pointer.axis events (horizontal and vertical) and finally a
     * wl_pointer.frame event. The client may use this information to
     * calculate a diagonal vector for scrolling.
     *
     * When multiple wl_pointer.axis events occur within the same frame,
     * the motion vector is the combined motion of all events.
     * When a wl_pointer.axis and a wl_pointer.axis_stop event occur within
     * the same frame, this indicates that axis movement in one axis has
     * stopped but continues in the other axis.
     * When multiple wl_pointer.axis_stop events occur within the same
     * frame, this indicates that these axes stopped in the same instance.
     *
     * A wl_pointer.frame event is sent for every logical event group,
     * even if the group only contains a single wl_pointer event.
     * Specifically, a client may get a sequence: motion, frame, button,
     * frame, axis, frame, axis_stop, frame.
     *
     * The wl_pointer.enter and wl_pointer.leave events are logical events
     * generated by the compositor and not the hardware. These events are
     * also grouped by a wl_pointer.frame. When a pointer moves from one
     * surface to another, a compositor should group the
     * wl_pointer.leave event within the same wl_pointer.frame.
     * However, a client must not rely on wl_pointer.leave and
     * wl_pointer.enter being in the same wl_pointer.frame.
     * Compositor-specific policies may require the wl_pointer.leave and
     * wl_pointer.enter event being split across multiple wl_pointer.frame
     * groups.
     *
     * Since version 5.
     */
    public fun sendFrame() {
        if (!isAlive || !client.isConnected) return
        if (version < 5) return // the client is too old for this event
        event(5).end()
    }

    /**
     * Axis source event
     *
     * Source information for scroll and other axes.
     *
     * This event does not occur on its own. It is sent before a
     * wl_pointer.frame event and carries the source information for
     * all events within that frame.
     *
     * The source specifies how this event was generated. If the source is
     * wl_pointer.axis_source.finger, a wl_pointer.axis_stop event will be
     * sent when the user lifts the finger off the device.
     *
     * If the source is wl_pointer.axis_source.wheel,
     * wl_pointer.axis_source.wheel_tilt or
     * wl_pointer.axis_source.continuous, a wl_pointer.axis_stop event may
     * or may not be sent. Whether a compositor sends an axis_stop event
     * for these sources is hardware-specific and implementation-dependent;
     * clients must not rely on receiving an axis_stop event for these
     * scroll sources and should treat scroll sequences from these scroll
     * sources as unterminated by default.
     *
     * This event is optional. If the source is unknown for a particular
     * axis event sequence, no event is sent.
     * Only one wl_pointer.axis_source event is permitted per frame.
     *
     * The order of wl_pointer.axis_discrete and wl_pointer.axis_source is
     * not guaranteed.
     *
     * Since version 5.
     */
    public fun sendAxisSource(axisSource: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 5) return // the client is too old for this event
        event(6).uint(axisSource).end()
    }

    /**
     * Axis stop event
     *
     * Stop notification for scroll and other axes.
     *
     * For some wl_pointer.axis_source types, a wl_pointer.axis_stop event
     * is sent to notify a client that the axis sequence has terminated.
     * This enables the client to implement kinetic scrolling.
     * See the wl_pointer.axis_source documentation for information on when
     * this event may be generated.
     *
     * Any wl_pointer.axis events with the same axis_source after this
     * event should be considered as the start of a new axis motion.
     *
     * The timestamp is to be interpreted identical to the timestamp in the
     * wl_pointer.axis event. The timestamp value may be the same as a
     * preceding wl_pointer.axis event.
     *
     * Since version 5.
     */
    public fun sendAxisStop(time: Int, axis: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 5) return // the client is too old for this event
        event(7).uint(time).uint(axis).end()
    }

    /**
     * Axis click event
     *
     * Discrete step information for scroll and other axes.
     *
     * This event carries the axis value of the wl_pointer.axis event in
     * discrete steps (e.g. mouse wheel clicks).
     *
     * This event is deprecated with wl_pointer version 8 - this event is not
     * sent to clients supporting version 8 or later.
     *
     * This event does not occur on its own, it is coupled with a
     * wl_pointer.axis event that represents this axis value on a
     * continuous scale. The protocol guarantees that each axis_discrete
     * event is always followed by exactly one axis event with the same
     * axis number within the same wl_pointer.frame. Note that the protocol
     * allows for other events to occur between the axis_discrete and
     * its coupled axis event, including other axis_discrete or axis
     * events. A wl_pointer.frame must not contain more than one axis_discrete
     * event per axis type.
     *
     * This event is optional; continuous scrolling devices
     * like two-finger scrolling on touchpads do not have discrete
     * steps and do not generate this event.
     *
     * The discrete value carries the directional information. e.g. a value
     * of -2 is two steps towards the negative direction of this axis.
     *
     * The axis number is identical to the axis number in the associated
     * axis event.
     *
     * The order of wl_pointer.axis_discrete and wl_pointer.axis_source is
     * not guaranteed.
     *
     * Since version 5.
     */
    public fun sendAxisDiscrete(axis: Int, discrete: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 5) return // the client is too old for this event
        event(8).uint(axis).int(discrete).end()
    }

    /**
     * Axis high-resolution scroll event
     *
     * Discrete high-resolution scroll information.
     *
     * This event carries high-resolution wheel scroll information,
     * with each multiple of 120 representing one logical scroll step
     * (a wheel detent). For example, an axis_value120 of 30 is one quarter of
     * a logical scroll step in the positive direction, a value120 of
     * -240 are two logical scroll steps in the negative direction within the
     * same hardware event.
     * Clients that rely on discrete scrolling should accumulate the
     * value120 to multiples of 120 before processing the event.
     *
     * The value120 must not be zero.
     *
     * This event replaces the wl_pointer.axis_discrete event in clients
     * supporting wl_pointer version 8 or later.
     *
     * Where a wl_pointer.axis_source event occurs in the same
     * wl_pointer.frame, the axis source applies to this event.
     *
     * The order of wl_pointer.axis_value120 and wl_pointer.axis_source is
     * not guaranteed.
     *
     * Since version 8.
     */
    public fun sendAxisValue120(axis: Int, value120: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 8) return // the client is too old for this event
        event(9).uint(axis).int(value120).end()
    }

    /**
     * Axis relative physical direction event
     *
     * Relative directional information of the entity causing the axis
     * motion.
     *
     * For a wl_pointer.axis event, the wl_pointer.axis_relative_direction
     * event specifies the movement direction of the entity causing the
     * wl_pointer.axis event. For example:
     * - if a user's fingers on a touchpad move down and this
     *   causes a wl_pointer.axis vertical_scroll down event, the physical
     *   direction is 'identical'
     * - if a user's fingers on a touchpad move down and this causes a
     *   wl_pointer.axis vertical_scroll up scroll up event ('natural
     *   scrolling'), the physical direction is 'inverted'.
     *
     * A client may use this information to adjust scroll motion of
     * components. Specifically, enabling natural scrolling causes the
     * content to change direction compared to traditional scrolling.
     * Some widgets like volume control sliders should usually match the
     * physical direction regardless of whether natural scrolling is
     * active. This event enables clients to match the scroll direction of
     * a widget to the physical direction.
     *
     * This event does not occur on its own, it is coupled with a
     * wl_pointer.axis event that represents this axis value.
     * The protocol guarantees that each axis_relative_direction event is
     * always followed by exactly one axis event with the same
     * axis number within the same wl_pointer.frame. Note that the protocol
     * allows for other events to occur between the axis_relative_direction
     * and its coupled axis event.
     *
     * The axis number is identical to the axis number in the associated
     * axis event.
     *
     * The order of wl_pointer.axis_relative_direction,
     * wl_pointer.axis_discrete and wl_pointer.axis_source is not
     * guaranteed.
     *
     * Since version 9.
     */
    public fun sendAxisRelativeDirection(axis: Int, direction: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 9) return // the client is too old for this event
        event(10).uint(axis).uint(direction).end()
    }

    /**
     * Pointer warp event
     *
     * Notification of pointer location change within a surface.
     *
     * This location change is not due to events on the input device,
     * but because either the surface under the pointer was moved and
     * thus the relative position of the pointer changed, or because
     * the compositor changed the pointer position in response to an
     * event like pointer confinement being exited.
     *
     * The arguments surface_x and surface_y are the location relative to
     * the focused surface.
     *
     * This event must not occur in the same wl_pointer.frame as a
     * wl_pointer.enter or wl_pointer.motion event.
     *
     * Since version 11.
     */
    public fun sendWarp(surfaceX: Double, surfaceY: Double) {
        if (!isAlive || !client.isConnected) return
        if (version < 11) return // the client is too old for this event
        event(11).fixed(surfaceX).fixed(surfaceY).end()
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    /**
     * Physical button state
     */
    public object ButtonState {
        /** the button is not pressed */
        public const val RELEASED: Int = 0
        /** the button is pressed */
        public const val PRESSED: Int = 1
    }

    /**
     * Axis types
     */
    public object Axis {
        /** vertical axis */
        public const val VERTICAL_SCROLL: Int = 0
        /** horizontal axis */
        public const val HORIZONTAL_SCROLL: Int = 1
    }

    /**
     * Axis source types
     */
    public object AxisSource {
        /** a physical wheel rotation */
        public const val WHEEL: Int = 0
        /** finger on a touch surface */
        public const val FINGER: Int = 1
        /** continuous coordinate space */
        public const val CONTINUOUS: Int = 2
        /** a physical wheel tilt */
        public const val WHEEL_TILT: Int = 3
    }

    /**
     * Axis relative direction
     */
    public object AxisRelativeDirection {
        /** physical motion matches axis direction */
        public const val IDENTICAL: Int = 0
        /** physical motion is the inverse of the axis direction */
        public const val INVERTED: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_pointer is not implemented")
        when (opcode) {
            0 -> {
                val serial = args.uint()
                val surface = args.objOrNull(WlSurface::class.java)
                val hotspotX = args.int()
                val hotspotY = args.int()
                args.end()
                handler.setCursor(this, serial, surface, hotspotX, hotspotY)
            }
            1 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_pointer.release needs version 3")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_pointer request $opcode")
        }
    }

    public companion object : ResourceType<WlPointer>("wl_pointer", 11) {
        internal override fun create(client: Client, id: Int, version: Int): WlPointer = WlPointer(client, id, version)
    }
}
