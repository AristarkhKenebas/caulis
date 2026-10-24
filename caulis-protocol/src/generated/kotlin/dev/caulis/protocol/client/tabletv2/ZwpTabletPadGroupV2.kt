// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * A set of buttons, rings and strips
 *
 * A pad group describes a distinct (sub)set of buttons, rings and strips
 * present in the tablet. The criteria of this grouping is usually positional,
 * eg. if a tablet has buttons on the left and right side, 2 groups will be
 * presented. The physical arrangement of groups is undisclosed and may
 * change on the fly.
 *
 * Pad groups will announce their features during pad initialization. Between
 * the corresponding zwp_tablet_pad_v2.group event and zwp_tablet_pad_group_v2.done, the
 * pad group will announce the buttons, rings and strips contained in it,
 * plus the number of supported modes.
 *
 * Modes are a mechanism to allow multiple groups of actions for every element
 * in the pad group. The number of groups and available modes in each is
 * persistent across device plugs. The current mode is user-switchable, it
 * will be announced through the zwp_tablet_pad_group_v2.mode_switch event both
 * whenever it is switched, and after zwp_tablet_pad_v2.enter.
 *
 * The current mode logically applies to all elements in the pad group,
 * although it is at clients' discretion whether to actually perform different
 * actions, and/or issue the respective .set_feedback requests to notify the
 * compositor. See the zwp_tablet_pad_group_v2.mode_switch event for more details.
 */
public class ZwpTabletPadGroupV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletPadGroupV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Buttons announced
         *
         * Sent on zwp_tablet_pad_group_v2 initialization to announce the available
         * buttons in the group. Button indices start at 0, a button may only be
         * in one group at a time.
         *
         * This event is first sent in the initial burst of events before the
         * zwp_tablet_pad_group_v2.done event.
         *
         * Some buttons are reserved by the compositor. These buttons may not be
         * assigned to any zwp_tablet_pad_group_v2. Compositors may broadcast this
         * event in the case of changes to the mapping of these reserved buttons.
         * If the compositor happens to reserve all buttons in a group, this event
         * will be sent with an empty array.
         */
        public fun buttons(proxy: ZwpTabletPadGroupV2, buttons: ByteArray) {}

        /**
         * Ring announced
         *
         * Sent on zwp_tablet_pad_group_v2 initialization to announce available rings.
         * One event is sent for each ring available on this pad group.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_pad_group_v2.done event.
         */
        public fun ring(proxy: ZwpTabletPadGroupV2, ring: ZwpTabletPadRingV2) {}

        /**
         * Strip announced
         *
         * Sent on zwp_tablet_pad_v2 initialization to announce available strips.
         * One event is sent for each strip available on this pad group.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_pad_group_v2.done event.
         */
        public fun strip(proxy: ZwpTabletPadGroupV2, strip: ZwpTabletPadStripV2) {}

        /**
         * Mode-switch ability announced
         *
         * Sent on zwp_tablet_pad_group_v2 initialization to announce that the pad
         * group may switch between modes. A client may use a mode to store a
         * specific configuration for buttons, rings and strips and use the
         * zwp_tablet_pad_group_v2.mode_switch event to toggle between these
         * configurations. Mode indices start at 0.
         *
         * Switching modes is compositor-dependent. See the
         * zwp_tablet_pad_group_v2.mode_switch event for more details.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_pad_group_v2.done event. This event is only sent when
         * more than one mode is available.
         */
        public fun modes(proxy: ZwpTabletPadGroupV2, modes: Int) {}

        /**
         * Tablet group description events sequence complete
         *
         * This event is sent immediately to signal the end of the initial
         * burst of descriptive events. A client may consider the static
         * description of the tablet to be complete and finalize initialization
         * of the tablet group.
         */
        public fun done(proxy: ZwpTabletPadGroupV2) {}

        /**
         * Mode switch event
         *
         * Notification that the mode was switched.
         *
         * A mode applies to all buttons, rings, strips and dials in a group
         * simultaneously, but a client is not required to assign different actions
         * for each mode. For example, a client may have mode-specific button
         * mappings but map the ring to vertical scrolling in all modes. Mode
         * indices start at 0.
         *
         * Switching modes is compositor-dependent. The compositor may provide
         * visual cues to the user about the mode, e.g. by toggling LEDs on
         * the tablet device. Mode-switching may be software-controlled or
         * controlled by one or more physical buttons. For example, on a Wacom
         * Intuos Pro, the button inside the ring may be assigned to switch
         * between modes.
         *
         * The compositor will also send this event after zwp_tablet_pad_v2.enter on
         * each group in order to notify of the current mode. Groups that only
         * feature one mode will use mode=0 when emitting this event.
         *
         * If a button action in the new mode differs from the action in the
         * previous mode, the client should immediately issue a
         * zwp_tablet_pad_v2.set_feedback request for each changed button.
         *
         * If a ring, strip or dial action in the new mode differs from the action
         * in the previous mode, the client should immediately issue a
         * zwp_tablet_ring_v2.set_feedback, zwp_tablet_strip_v2.set_feedback or
         * zwp_tablet_dial_v2.set_feedback request for each changed ring, strip or dial.
         */
        public fun modeSwitch(proxy: ZwpTabletPadGroupV2, time: Int, serial: Int, mode: Int) {}

        /**
         * Dial announced
         *
         * Sent on zwp_tablet_pad_v2 initialization to announce available dials.
         * One event is sent for each dial available on this pad group.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_pad_group_v2.done event.
         *
         * Since version 2.
         */
        public fun dial(proxy: ZwpTabletPadGroupV2, dial: ZwpTabletPadDialV2) {}
    }

    /**
     * Destroy the pad object
     *
     * Destroy the zwp_tablet_pad_group_v2 object. Objects created from this object
     * are unaffected and should be destroyed separately.
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
                val buttons = args.array()
                args.end()
                if (Wire.tracing) Wire.received(this, "buttons", "buttons=${Wire.show(buttons)}")
                handler?.buttons(this, buttons)
            }
            1 -> {
                val ring = args.newId(ZwpTabletPadRingV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "ring", "ring=${Wire.show(ring)}")
                handler?.ring(this, ring)
            }
            2 -> {
                val strip = args.newId(ZwpTabletPadStripV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "strip", "strip=${Wire.show(strip)}")
                handler?.strip(this, strip)
            }
            3 -> {
                val modes = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "modes", "modes=${Wire.show(modes)}")
                handler?.modes(this, modes)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            5 -> {
                val time = args.uint()
                val serial = args.uint()
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "mode_switch", "time=${Wire.show(time)}, serial=${Wire.show(serial)}, mode=${Wire.show(mode)}")
                handler?.modeSwitch(this, time, serial, mode)
            }
            6 -> {
                val dial = args.newId(ZwpTabletPadDialV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "dial", "dial=${Wire.show(dial)}")
                handler?.dial(this, dial)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletPadGroupV2>("zwp_tablet_pad_group_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletPadGroupV2 = ZwpTabletPadGroupV2(connection, id, version)
    }
}
