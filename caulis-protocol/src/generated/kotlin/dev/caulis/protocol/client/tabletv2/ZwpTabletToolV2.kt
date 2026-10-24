// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class ZwpTabletToolV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletToolV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Tool type
         *
         * The tool type is the high-level type of the tool and usually decides
         * the interaction expected from this tool.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_tool_v2.done event.
         */
        public fun type(proxy: ZwpTabletToolV2, toolType: Int) {}

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
        public fun hardwareSerial(proxy: ZwpTabletToolV2, hardwareSerialHi: Int, hardwareSerialLo: Int) {}

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
        public fun hardwareIdWacom(proxy: ZwpTabletToolV2, hardwareIdHi: Int, hardwareIdLo: Int) {}

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
        public fun capability(proxy: ZwpTabletToolV2, capability: Int) {}

        /**
         * Tool description events sequence complete
         *
         * This event signals the end of the initial burst of descriptive
         * events. A client may consider the static description of the tool to
         * be complete and finalize initialization of the tool.
         */
        public fun done(proxy: ZwpTabletToolV2) {}

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
        public fun removed(proxy: ZwpTabletToolV2) {}

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
        public fun proximityIn(proxy: ZwpTabletToolV2, serial: Int, tablet: ZwpTabletV2, surface: WlSurface) {}

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
        public fun proximityOut(proxy: ZwpTabletToolV2) {}

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
        public fun down(proxy: ZwpTabletToolV2, serial: Int) {}

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
        public fun up(proxy: ZwpTabletToolV2) {}

        /**
         * Motion event
         *
         * Sent whenever a tablet tool moves.
         */
        public fun motion(proxy: ZwpTabletToolV2, x: Double, y: Double) {}

        /**
         * Pressure change event
         *
         * Sent whenever the pressure axis on a tool changes. The value of this
         * event is normalized to a value between 0 and 65535.
         *
         * Note that pressure may be nonzero even when a tool is not in logical
         * contact. See the down and up events for more details.
         */
        public fun pressure(proxy: ZwpTabletToolV2, pressure: Int) {}

        /**
         * Distance change event
         *
         * Sent whenever the distance axis on a tool changes. The value of this
         * event is normalized to a value between 0 and 65535.
         *
         * Note that distance may be nonzero even when a tool is not in logical
         * contact. See the down and up events for more details.
         */
        public fun distance(proxy: ZwpTabletToolV2, distance: Int) {}

        /**
         * Tilt change event
         *
         * Sent whenever one or both of the tilt axes on a tool change. Each tilt
         * value is in degrees, relative to the z-axis of the tablet.
         * The angle is positive when the top of a tool tilts along the
         * positive x or y axis.
         */
        public fun tilt(proxy: ZwpTabletToolV2, tiltX: Double, tiltY: Double) {}

        /**
         * Z-rotation change event
         *
         * Sent whenever the z-rotation axis on the tool changes. The
         * rotation value is in degrees clockwise from the tool's
         * logical neutral position.
         */
        public fun rotation(proxy: ZwpTabletToolV2, degrees: Double) {}

        /**
         * Slider position change event
         *
         * Sent whenever the slider position on the tool changes. The
         * value is normalized between -65535 and 65535, with 0 as the logical
         * neutral position of the slider.
         *
         * The slider is available on e.g. the Wacom Airbrush tool.
         */
        public fun slider(proxy: ZwpTabletToolV2, position: Int) {}

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
        public fun wheel(proxy: ZwpTabletToolV2, degrees: Double, clicks: Int) {}

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
        public fun button(proxy: ZwpTabletToolV2, serial: Int, button: Int, state: Int) {}

        /**
         * Frame event
         *
         * Marks the end of a series of axis and/or button updates from the
         * tablet. The Wayland protocol requires axis updates to be sent
         * sequentially, however all events within a frame should be considered
         * one hardware event.
         */
        public fun frame(proxy: ZwpTabletToolV2, time: Int) {}
    }

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
    public fun setCursor(serial: Int, surface: WlSurface?, hotspotX: Int, hotspotY: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_cursor", "serial=${Wire.show(serial)}, surface=${Wire.show(surface)}, hotspot_x=${Wire.show(hotspotX)}, hotspot_y=${Wire.show(hotspotY)}")
        request(0).uint(serial).obj(surface).int(hotspotX).int(hotspotY).end()
    }

    /**
     * Destroy the tool object
     *
     * This destroys the client's resource for this tool object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    /** a physical tool type */
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

    /** capability flags for a tool */
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

    /** physical button state */
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val toolType = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "type", "tool_type=${Wire.show(toolType)}")
                handler?.type(this, toolType)
            }
            1 -> {
                val hardwareSerialHi = args.uint()
                val hardwareSerialLo = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "hardware_serial", "hardware_serial_hi=${Wire.show(hardwareSerialHi)}, hardware_serial_lo=${Wire.show(hardwareSerialLo)}")
                handler?.hardwareSerial(this, hardwareSerialHi, hardwareSerialLo)
            }
            2 -> {
                val hardwareIdHi = args.uint()
                val hardwareIdLo = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "hardware_id_wacom", "hardware_id_hi=${Wire.show(hardwareIdHi)}, hardware_id_lo=${Wire.show(hardwareIdLo)}")
                handler?.hardwareIdWacom(this, hardwareIdHi, hardwareIdLo)
            }
            3 -> {
                val capability = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "capability", "capability=${Wire.show(capability)}")
                handler?.capability(this, capability)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            5 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "removed", "")
                handler?.removed(this)
            }
            6 -> {
                val serial = args.uint()
                val tablet = args.obj(ZwpTabletV2::class.java)
                val surface = args.obj(WlSurface::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "proximity_in", "serial=${Wire.show(serial)}, tablet=${Wire.show(tablet)}, surface=${Wire.show(surface)}")
                handler?.proximityIn(this, serial, tablet, surface)
            }
            7 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "proximity_out", "")
                handler?.proximityOut(this)
            }
            8 -> {
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "down", "serial=${Wire.show(serial)}")
                handler?.down(this, serial)
            }
            9 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "up", "")
                handler?.up(this)
            }
            10 -> {
                val x = args.fixed()
                val y = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "motion", "x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.motion(this, x, y)
            }
            11 -> {
                val pressure = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "pressure", "pressure=${Wire.show(pressure)}")
                handler?.pressure(this, pressure)
            }
            12 -> {
                val distance = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "distance", "distance=${Wire.show(distance)}")
                handler?.distance(this, distance)
            }
            13 -> {
                val tiltX = args.fixed()
                val tiltY = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "tilt", "tilt_x=${Wire.show(tiltX)}, tilt_y=${Wire.show(tiltY)}")
                handler?.tilt(this, tiltX, tiltY)
            }
            14 -> {
                val degrees = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "rotation", "degrees=${Wire.show(degrees)}")
                handler?.rotation(this, degrees)
            }
            15 -> {
                val position = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "slider", "position=${Wire.show(position)}")
                handler?.slider(this, position)
            }
            16 -> {
                val degrees = args.fixed()
                val clicks = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "wheel", "degrees=${Wire.show(degrees)}, clicks=${Wire.show(clicks)}")
                handler?.wheel(this, degrees, clicks)
            }
            17 -> {
                val serial = args.uint()
                val button = args.uint()
                val state = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "button", "serial=${Wire.show(serial)}, button=${Wire.show(button)}, state=${Wire.show(state)}")
                handler?.button(this, serial, button, state)
            }
            18 -> {
                val time = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "frame", "time=${Wire.show(time)}")
                handler?.frame(this, time)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletToolV2>("zwp_tablet_tool_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletToolV2 = ZwpTabletToolV2(connection, id, version)
    }
}
