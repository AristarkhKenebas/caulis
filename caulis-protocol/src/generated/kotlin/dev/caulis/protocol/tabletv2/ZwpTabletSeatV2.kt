// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.tabletv2

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Controller object for graphic tablet devices of a seat
 *
 * An object that provides access to the graphics tablets available on this
 * seat. After binding to this interface, the compositor sends a set of
 * zwp_tablet_seat_v2.tablet_added and zwp_tablet_seat_v2.tool_added events.
 */
public class ZwpTabletSeatV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletSeatV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_seat_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Release the memory for the tablet seat object
         *
         * Destroy the zwp_tablet_seat_v2 object. Objects created from this
         * object are unaffected and should be destroyed separately.
         */
        public fun destroy(resource: ZwpTabletSeatV2)
    }

    /**
     * New device notification
     *
     * This event is sent whenever a new tablet becomes available on this
     * seat. This event only provides the object id of the tablet, any
     * static information about the tablet (device name, vid/pid, etc.) is
     * sent through the zwp_tablet_v2 interface.
     */
    public fun sendTabletAdded(id: ZwpTabletV2) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "tablet_added", "id=${Wire.show(id)}")
        event(0).newId(id).end()
    }

    /**
     * A new tool has been used with a tablet
     *
     * This event is sent whenever a tool that has not previously been used
     * with a tablet comes into use. This event only provides the object id
     * of the tool; any static information about the tool (capabilities,
     * type, etc.) is sent through the zwp_tablet_tool_v2 interface.
     */
    public fun sendToolAdded(id: ZwpTabletToolV2) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "tool_added", "id=${Wire.show(id)}")
        event(1).newId(id).end()
    }

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
    public fun sendPadAdded(id: ZwpTabletPadV2) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "pad_added", "id=${Wire.show(id)}")
        event(2).newId(id).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_seat_v2 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_seat_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletSeatV2>("zwp_tablet_seat_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletSeatV2 = ZwpTabletSeatV2(client, id, version)
    }
}
