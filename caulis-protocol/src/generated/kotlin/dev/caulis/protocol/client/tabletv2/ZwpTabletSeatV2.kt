// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Controller object for graphic tablet devices of a seat
 *
 * An object that provides access to the graphics tablets available on this
 * seat. After binding to this interface, the compositor sends a set of
 * zwp_tablet_seat_v2.tablet_added and zwp_tablet_seat_v2.tool_added events.
 */
public class ZwpTabletSeatV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletSeatV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * New device notification
         *
         * This event is sent whenever a new tablet becomes available on this
         * seat. This event only provides the object id of the tablet, any
         * static information about the tablet (device name, vid/pid, etc.) is
         * sent through the zwp_tablet_v2 interface.
         */
        public fun tabletAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletV2) {}

        /**
         * A new tool has been used with a tablet
         *
         * This event is sent whenever a tool that has not previously been used
         * with a tablet comes into use. This event only provides the object id
         * of the tool; any static information about the tool (capabilities,
         * type, etc.) is sent through the zwp_tablet_tool_v2 interface.
         */
        public fun toolAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletToolV2) {}

        /**
         * New pad notification
         *
         * This event is sent whenever a new pad is known to the system. Typically,
         * pads are physically attached to tablets and a pad_added event is
         * sent immediately after the zwp_tablet_seat_v2.tablet_added.
         * However, some standalone pad devices logically attach to tablets at
         * runtime, and the client must wait for zwp_tablet_pad_v2.enter to know
         * the tablet a pad is attached to.
         *
         * This event only provides the object id of the pad. All further
         * features (buttons, strips, rings) are sent through the zwp_tablet_pad_v2
         * interface.
         */
        public fun padAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletPadV2) {}
    }

    /**
     * Release the memory for the tablet seat object
     *
     * Destroy the zwp_tablet_seat_v2 object. Objects created from this
     * object are unaffected and should be destroyed separately.
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
                val id = args.newId(ZwpTabletV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "tablet_added", "id=${Wire.show(id)}")
                handler?.tabletAdded(this, id)
            }
            1 -> {
                val id = args.newId(ZwpTabletToolV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "tool_added", "id=${Wire.show(id)}")
                handler?.toolAdded(this, id)
            }
            2 -> {
                val id = args.newId(ZwpTabletPadV2, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "pad_added", "id=${Wire.show(id)}")
                handler?.padAdded(this, id)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletSeatV2>("zwp_tablet_seat_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletSeatV2 = ZwpTabletSeatV2(connection, id, version)
    }
}
