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
import dev.caulis.protocol.wayland.WlSeat

/**
 * Controller object for graphic tablet devices
 *
 * An object that provides access to the graphics tablets available on this
 * system. All tablets are associated with a seat, to get access to the
 * actual tablets, use zwp_tablet_manager_v2.get_tablet_seat.
 */
public class ZwpTabletManagerV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletManagerV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_manager_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Get the tablet seat
         *
         * Get the zwp_tablet_seat_v2 object for the given seat. This object
         * provides access to all graphics tablets in this seat.
         */
        public fun getTabletSeat(resource: ZwpTabletManagerV2, tabletSeat: ZwpTabletSeatV2, seat: WlSeat)

        /**
         * Release the memory for the tablet manager object
         *
         * Destroy the zwp_tablet_manager_v2 object. Objects created from this
         * object are unaffected and should be destroyed separately.
         */
        public fun destroy(resource: ZwpTabletManagerV2)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_manager_v2 is not implemented")
        when (opcode) {
            0 -> {
                val tabletSeatId = args.newId()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val tabletSeat = client.register(ZwpTabletSeatV2.create(client, tabletSeatId, version))
                if (Wire.tracing) Wire.request(this, "get_tablet_seat", "tablet_seat=${Wire.show(tabletSeat)}, seat=${Wire.show(seat)}")
                handler.getTabletSeat(this, tabletSeat, seat)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_manager_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletManagerV2>("zwp_tablet_manager_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletManagerV2 = ZwpTabletManagerV2(client, id, version)
    }
}
