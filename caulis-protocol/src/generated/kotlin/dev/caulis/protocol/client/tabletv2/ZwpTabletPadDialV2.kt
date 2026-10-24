// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Pad dial
 *
 * A rotary control, e.g. a dial or a wheel.
 *
 * Events on a dial are logically grouped by the zwp_tablet_pad_dial_v2.frame
 * event.
 */
public class ZwpTabletPadDialV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletPadDialV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun delta(proxy: ZwpTabletPadDialV2, value120: Int) {}

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
        public fun frame(proxy: ZwpTabletPadDialV2, time: Int) {}
    }

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
    public fun setFeedback(description: String, serial: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_feedback", "description=${Wire.show(description)}, serial=${Wire.show(serial)}")
        request(0).string(description).uint(serial).end()
    }

    /**
     * Destroy the dial object
     *
     * This destroys the client's resource for this dial object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val value120 = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "delta", "value120=${Wire.show(value120)}")
                handler?.delta(this, value120)
            }
            1 -> {
                val time = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "frame", "time=${Wire.show(time)}")
                handler?.frame(this, time)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletPadDialV2>("zwp_tablet_pad_dial_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletPadDialV2 = ZwpTabletPadDialV2(connection, id, version)
    }
}
