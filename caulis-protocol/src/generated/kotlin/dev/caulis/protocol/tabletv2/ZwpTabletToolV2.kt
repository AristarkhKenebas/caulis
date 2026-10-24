// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.tabletv2

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * A physical tablet tool
 *
 * An object that represents a physical tool that has been, or is
 * currently in use with a tablet in this seat. Each zwp_tablet_tool_v2
 * object stays valid until the client destroys it; the compositor
 * reuses the zwp_tablet_tool_v2 object to indicate that the object's
 * respective physical tool has come into proximity of a tablet again.
 *
 * A zwp_tablet_tool_v2 object's relation to a physical tool depends on the
 * tablet's ability to report serial numbers. If the tablet supports
 * this capability, then the object represents a specific physical tool
 * and can be identified even when used on multiple tablets.
 *
 * A tablet tool has a number of static characteristics, e.g. tool type,
 * hardware_serial and capabilities. These capabilities are sent in an
 * event sequence after the zwp_tablet_seat_v2.tool_added event before any
 * actual events from this tool. This initial event sequence is
 * terminated by a zwp_tablet_tool_v2.done event.
 *
 * Tablet tool events are grouped by zwp_tablet_tool_v2.frame events.
 * Any events received before a zwp_tablet_tool_v2.frame event should be
 * considered part of the same hardware state change.
 */
public class ZwpTabletToolV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletToolV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_tool_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set the tablet tool's surface
         *
         * Sets the surface of the cursor used for this tool on the given
         * tablet. This request only takes effect if the tool is in proximity
         * of one of the requesting client's surfaces or the surface parameter
         * is the current pointer surface. If there was a previous surface set
         * with this request it is replaced. If surface is NULL, the cursor
         * image is hidden.
         *
         * The parameters hotspot_x and hotspot_y define the position of the
         * pointer surface relative to the pointer location. Its top-left corner
         * is always at (x, y) - (hotspot_x, hotspot_y), where (x, y) are the
         * coordinates of the pointer location, in surface-local coordinates.
         *
         * On surface.attach requests to the pointer surface, hotspot_x and
         * hotspot_y are decremented by the x and y parameters passed to the
         * request. Attach must be confirmed by wl_surface.commit as usual.
         *
         * The hotspot can also be updated by passing the currently set pointer
         * surface to this request with new values for hotspot_x and hotspot_y.
         *
         * The current and pending input regions of the wl_surface are cleared,
         * and wl_surface.set_input_region is ignored until the wl_surface is no
         * longer used as the cursor. When the use as a cursor ends, the current
         * and pending input regions become undefined, and the wl_surface is
         * unmapped.
         *
         * This request gives the surface the role of a zwp_tablet_tool_v2 cursor. A
         * surface may only ever be used as the cursor surface for one
         * zwp_tablet_tool_v2. If the surface already has another role or has
         * previously been used as cursor surface for a different tool, a
         * protocol error is raised.
         */
        public fun setCursor(resource: ZwpTabletToolV2, serial: Int, surface: WlSurface?, hotspotX: Int, hotspotY: Int)

        /**
         * Destroy the tool object
         *
         * This destroys the client's resource for this tool object.
         */
        public fun destroy(resource: ZwpTabletToolV2)
    }

    /**
     * Tool type
     *
     * The tool type is the high-level type of the tool and usually decides
     * the interaction expected from this tool.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_tool_v2.done event.
     */
    public fun sendType(toolType: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(toolType).end()
    }

    /**
     * Unique hardware serial number of the tool
     *
     * If the physical tool can be identified by a unique 64-bit serial
     * number, this event notifies the client of this serial number.
     *
     * If multiple tablets are available in the same seat and the tool is
     * uniquely identifiable by the serial number, that tool may move
     * between tablets.
     *
     * Otherwise, if the tool has no serial number and this event is
     * missing, the tool is tied to the tablet it first comes into
     * proximity with. Even if the physical tool is used on multiple
     * tablets, separate zwp_tablet_tool_v2 objects will be created, one per
     * tablet.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_tool_v2.done event.
     */
    public fun sendHardwareSerial(hardwareSerialHi: Int, hardwareSerialLo: Int) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(hardwareSerialHi).uint(hardwareSerialLo).end()
    }

    /**
     * Hardware id notification in Wacom's format
     *
     * This event notifies the client of a hardware id available on this tool.
     *
     * The hardware id is a device-specific 64-bit id that provides extra
     * information about the tool in use, beyond the wl_tool.type
     * enumeration. The format of the id is specific to tablets made by
     * Wacom Inc. For example, the hardware id of a Wacom Grip
     * Pen (a stylus) is 0x802.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_tool_v2.done event.
     */
    public fun sendHardwareIdWacom(hardwareIdHi: Int, hardwareIdLo: Int) {
        if (!isAlive || !client.isConnected) return
        event(2).uint(hardwareIdHi).uint(hardwareIdLo).end()
    }

    /**
     * Tool capability notification
     *
     * This event notifies the client of any capabilities of this tool,
     * beyond the main set of x/y axes and tip up/down detection.
     *
     * One event is sent for each extra capability available on this tool.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_tool_v2.done event.
     */
    public fun sendCapability(capability: Int) {
        if (!isAlive || !client.isConnected) return
        event(3).uint(capability).end()
    }

    /**
     * Tool description events sequence complete
     *
     * This event signals the end of the initial burst of descriptive
     * events. A client may consider the static description of the tool to
     * be complete and finalize initialization of the tool.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        event(4).end()
    }

    /**
     * Tool removed
     *
     * This event is sent when the tool is removed from the system and will
     * send no further events. Should the physical tool come back into
     * proximity later, a new zwp_tablet_tool_v2 object will be created.
     *
     * It is compositor-dependent when a tool is removed. A compositor may
     * remove a tool on proximity out, tablet removal or any other reason.
     * A compositor may also keep a tool alive until shutdown.
     *
     * If the tool is currently in proximity, a proximity_out event will be
     * sent before the removed event. See zwp_tablet_tool_v2.proximity_out for
     * the handling of any buttons logically down.
     *
     * When this event is received, the client must zwp_tablet_tool_v2.destroy
     * the object.
     */
    public fun sendRemoved() {
        if (!isAlive || !client.isConnected) return
        event(5).end()
    }

    /**
     * Proximity in event
     *
     * Notification that this tool is focused on a certain surface.
     *
     * This event can be received when the tool has moved from one surface to
     * another, or when the tool has come back into proximity above the
     * surface.
     *
     * If any button is logically down when the tool comes into proximity,
     * the respective button event is sent after the proximity_in event but
     * within the same frame as the proximity_in event.
     */
    public fun sendProximityIn(serial: Int, tablet: ZwpTabletV2, surface: WlSurface) {
        if (!isAlive || !client.isConnected) return
        event(6).uint(serial).obj(tablet).obj(surface).end()
    }

    /**
     * Proximity out event
     *
     * Notification that this tool has either left proximity, or is no
     * longer focused on a certain surface.
     *
     * When the tablet tool leaves proximity of the tablet, button release
     * events are sent for each button that was held down at the time of
     * leaving proximity. These events are sent before the proximity_out
     * event but within the same zwp_tablet_v2.frame.
     *
     * If the tool stays within proximity of the tablet, but the focus
     * changes from one surface to another, a button release event may not
     * be sent until the button is actually released or the tool leaves the
     * proximity of the tablet.
     */
    public fun sendProximityOut() {
        if (!isAlive || !client.isConnected) return
        event(7).end()
    }

    /**
     * Tablet tool is making contact
     *
     * Sent whenever the tablet tool comes in contact with the surface of the
     * tablet.
     *
     * If the tool is already in contact with the tablet when entering the
     * input region, the client owning said region will receive a
     * zwp_tablet_v2.proximity_in event, followed by a zwp_tablet_v2.down
     * event and a zwp_tablet_v2.frame event.
     *
     * Note that this event describes logical contact, not physical
     * contact. On some devices, a compositor may not consider a tool in
     * logical contact until a minimum physical pressure threshold is
     * exceeded.
     */
    public fun sendDown(serial: Int) {
        if (!isAlive || !client.isConnected) return
        event(8).uint(serial).end()
    }

    /**
     * Tablet tool is no longer making contact
     *
     * Sent whenever the tablet tool stops making contact with the surface of
     * the tablet, or when the tablet tool moves out of the input region
     * and the compositor grab (if any) is dismissed.
     *
     * If the tablet tool moves out of the input region while in contact
     * with the surface of the tablet and the compositor does not have an
     * ongoing grab on the surface, the client owning said region will
     * receive a zwp_tablet_v2.up event, followed by a zwp_tablet_v2.proximity_out
     * event and a zwp_tablet_v2.frame event. If the compositor has an ongoing
     * grab on this device, this event sequence is sent whenever the grab
     * is dismissed in the future.
     *
     * Note that this event describes logical contact, not physical
     * contact. On some devices, a compositor may not consider a tool out
     * of logical contact until physical pressure falls below a specific
     * threshold.
     */
    public fun sendUp() {
        if (!isAlive || !client.isConnected) return
        event(9).end()
    }

    /**
     * Motion event
     *
     * Sent whenever a tablet tool moves.
     */
    public fun sendMotion(x: Double, y: Double) {
        if (!isAlive || !client.isConnected) return
        event(10).fixed(x).fixed(y).end()
    }

    /**
     * Pressure change event
     *
     * Sent whenever the pressure axis on a tool changes. The value of this
     * event is normalized to a value between 0 and 65535.
     *
     * Note that pressure may be nonzero even when a tool is not in logical
     * contact. See the down and up events for more details.
     */
    public fun sendPressure(pressure: Int) {
        if (!isAlive || !client.isConnected) return
        event(11).uint(pressure).end()
    }

    /**
     * Distance change event
     *
     * Sent whenever the distance axis on a tool changes. The value of this
     * event is normalized to a value between 0 and 65535.
     *
     * Note that distance may be nonzero even when a tool is not in logical
     * contact. See the down and up events for more details.
     */
    public fun sendDistance(distance: Int) {
        if (!isAlive || !client.isConnected) return
        event(12).uint(distance).end()
    }

    /**
     * Tilt change event
     *
     * Sent whenever one or both of the tilt axes on a tool change. Each tilt
     * value is in degrees, relative to the z-axis of the tablet.
     * The angle is positive when the top of a tool tilts along the
     * positive x or y axis.
     */
    public fun sendTilt(tiltX: Double, tiltY: Double) {
        if (!isAlive || !client.isConnected) return
        event(13).fixed(tiltX).fixed(tiltY).end()
    }

    /**
     * Z-rotation change event
     *
     * Sent whenever the z-rotation axis on the tool changes. The
     * rotation value is in degrees clockwise from the tool's
     * logical neutral position.
     */
    public fun sendRotation(degrees: Double) {
        if (!isAlive || !client.isConnected) return
        event(14).fixed(degrees).end()
    }

    /**
     * Slider position change event
     *
     * Sent whenever the slider position on the tool changes. The
     * value is normalized between -65535 and 65535, with 0 as the logical
     * neutral position of the slider.
     *
     * The slider is available on e.g. the Wacom Airbrush tool.
     */
    public fun sendSlider(position: Int) {
        if (!isAlive || !client.isConnected) return
        event(15).int(position).end()
    }

    /**
     * Wheel delta event
     *
     * Sent whenever the wheel on the tool emits an event. This event
     * contains two values for the same axis change. The degrees value is
     * in the same orientation as the wl_pointer.vertical_scroll axis. The
     * clicks value is in discrete logical clicks of the mouse wheel. This
     * value may be zero if the movement of the wheel was less
     * than one logical click.
     *
     * Clients should choose either value and avoid mixing degrees and
     * clicks. The compositor may accumulate values smaller than a logical
     * click and emulate click events when a certain threshold is met.
     * Thus, zwp_tablet_tool_v2.wheel events with non-zero clicks values may
     * have different degrees values.
     */
    public fun sendWheel(degrees: Double, clicks: Int) {
        if (!isAlive || !client.isConnected) return
        event(16).fixed(degrees).int(clicks).end()
    }

    /**
     * Button event
     *
     * Sent whenever a button on the tool is pressed or released.
     *
     * If a button is held down when the tool moves in or out of proximity,
     * button events are generated by the compositor. See
     * zwp_tablet_tool_v2.proximity_in and zwp_tablet_tool_v2.proximity_out for
     * details.
     */
    public fun sendButton(serial: Int, button: Int, state: Int) {
        if (!isAlive || !client.isConnected) return
        event(17).uint(serial).uint(button).uint(state).end()
    }

    /**
     * Frame event
     *
     * Marks the end of a series of axis and/or button updates from the
     * tablet. The Wayland protocol requires axis updates to be sent
     * sequentially, however all events within a frame should be considered
     * one hardware event.
     */
    public fun sendFrame(time: Int) {
        if (!isAlive || !client.isConnected) return
        event(18).uint(time).end()
    }

    /**
     * A physical tool type
     */
    public object Type {
        /** Pen */
        public const val PEN: Int = 320
        /** Eraser */
        public const val ERASER: Int = 321
        /** Brush */
        public const val BRUSH: Int = 322
        /** Pencil */
        public const val PENCIL: Int = 323
        /** Airbrush */
        public const val AIRBRUSH: Int = 324
        /** Finger */
        public const val FINGER: Int = 325
        /** Mouse */
        public const val MOUSE: Int = 326
        /** Lens */
        public const val LENS: Int = 327
    }

    /**
     * Capability flags for a tool
     */
    public object Capability {
        /** Tilt axes */
        public const val TILT: Int = 1
        /** Pressure axis */
        public const val PRESSURE: Int = 2
        /** Distance axis */
        public const val DISTANCE: Int = 3
        /** Z-rotation axis */
        public const val ROTATION: Int = 4
        /** Slider axis */
        public const val SLIDER: Int = 5
        /** Wheel axis */
        public const val WHEEL: Int = 6
    }

    /**
     * Physical button state
     */
    public object ButtonState {
        /** button is not pressed */
        public const val RELEASED: Int = 0
        /** button is pressed */
        public const val PRESSED: Int = 1
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_tool_v2 is not implemented")
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
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_tool_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletToolV2>("zwp_tablet_tool_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletToolV2 = ZwpTabletToolV2(client, id, version)
    }
}
