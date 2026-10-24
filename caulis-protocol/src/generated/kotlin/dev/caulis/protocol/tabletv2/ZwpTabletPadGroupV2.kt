// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.tabletv2

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay

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
public class ZwpTabletPadGroupV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletPadGroupV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_pad_group_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the pad object
         *
         * Destroy the zwp_tablet_pad_group_v2 object. Objects created from this object
         * are unaffected and should be destroyed separately.
         */
        public fun destroy(resource: ZwpTabletPadGroupV2)
    }

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
    public fun sendButtons(buttons: ByteArray) {
        if (!isAlive || !client.isConnected) return
        event(0).array(buttons).end()
    }

    /**
     * Ring announced
     *
     * Sent on zwp_tablet_pad_group_v2 initialization to announce available rings.
     * One event is sent for each ring available on this pad group.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_pad_group_v2.done event.
     */
    public fun sendRing(ring: ZwpTabletPadRingV2) {
        if (!isAlive || !client.isConnected) return
        event(1).newId(ring).end()
    }

    /**
     * Strip announced
     *
     * Sent on zwp_tablet_pad_v2 initialization to announce available strips.
     * One event is sent for each strip available on this pad group.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_pad_group_v2.done event.
     */
    public fun sendStrip(strip: ZwpTabletPadStripV2) {
        if (!isAlive || !client.isConnected) return
        event(2).newId(strip).end()
    }

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
    public fun sendModes(modes: Int) {
        if (!isAlive || !client.isConnected) return
        event(3).uint(modes).end()
    }

    /**
     * Tablet group description events sequence complete
     *
     * This event is sent immediately to signal the end of the initial
     * burst of descriptive events. A client may consider the static
     * description of the tablet to be complete and finalize initialization
     * of the tablet group.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        event(4).end()
    }

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
    public fun sendModeSwitch(time: Int, serial: Int, mode: Int) {
        if (!isAlive || !client.isConnected) return
        event(5).uint(time).uint(serial).uint(mode).end()
    }

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
    public fun sendDial(dial: ZwpTabletPadDialV2) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        event(6).newId(dial).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_pad_group_v2 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_pad_group_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletPadGroupV2>("zwp_tablet_pad_group_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletPadGroupV2 = ZwpTabletPadGroupV2(client, id, version)
    }
}
