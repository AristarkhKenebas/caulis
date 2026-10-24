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
 * Pad dial
 *
 * A rotary control, e.g. a dial or a wheel.
 *
 * Events on a dial are logically grouped by the zwp_tablet_pad_dial_v2.frame
 * event.
 */
public class ZwpTabletPadDialV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletPadDialV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_pad_dial_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set compositor feedback
         *
         * Requests the compositor to use the provided feedback string
         * associated with this dial. This request should be issued immediately
         * after a zwp_tablet_pad_group_v2.mode_switch event from the corresponding
         * group is received, or whenever the dial is mapped to a different
         * action. See zwp_tablet_pad_group_v2.mode_switch for more details.
         *
         * Clients are encouraged to provide context-aware descriptions for
         * the actions associated with the dial, and compositors may use this
         * information to offer visual feedback about the button layout
         * (eg. on-screen displays).
         *
         * The provided string 'description' is a UTF-8 encoded string to be
         * associated with this ring, and is considered user-visible; general
         * internationalization rules apply.
         *
         * The serial argument will be that of the last
         * zwp_tablet_pad_group_v2.mode_switch event received for the group of this
         * dial. Requests providing other serials than the most recent one will be
         * ignored.
         */
        public fun setFeedback(resource: ZwpTabletPadDialV2, description: String, serial: Int)

        /**
         * Destroy the dial object
         *
         * This destroys the client's resource for this dial object.
         */
        public fun destroy(resource: ZwpTabletPadDialV2)
    }

    /**
     * Delta movement
     *
     * Sent whenever the position on a dial changes.
     *
     * This event carries the wheel delta as multiples or fractions
     * of 120 with each multiple of 120 representing one logical wheel detent.
     * For example, an axis_value120 of 30 is one quarter of
     * a logical wheel step in the positive direction, a value120 of
     * -240 are two logical wheel steps in the negative direction within the
     * same hardware event. See the wl_pointer.axis_value120 for more details.
     *
     * The value120 must not be zero.
     */
    public fun sendDelta(value120: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).int(value120).end()
    }

    /**
     * End of a dial event sequence
     *
     * Indicates the end of a set of events that represent one logical
     * hardware dial event. A client is expected to accumulate the data
     * in all events within the frame before proceeding.
     *
     * All zwp_tablet_pad_dial_v2 events before a zwp_tablet_pad_dial_v2.frame event belong
     * logically together.
     *
     * A zwp_tablet_pad_dial_v2.frame event is sent for every logical event
     * group, even if the group only contains a single zwp_tablet_pad_dial_v2
     * event. Specifically, a client may get a sequence: delta, frame,
     * delta, frame, etc.
     */
    public fun sendFrame(time: Int) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(time).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_pad_dial_v2 is not implemented")
        when (opcode) {
            0 -> {
                val description = args.string()
                val serial = args.uint()
                args.end()
                handler.setFeedback(this, description, serial)
            }
            1 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_pad_dial_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletPadDialV2>("zwp_tablet_pad_dial_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletPadDialV2 = ZwpTabletPadDialV2(client, id, version)
    }
}
