// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat

/**
 * Controller object for graphic tablet devices
 *
 * An object that provides access to the graphics tablets available on this
 * system. All tablets are associated with a seat, to get access to the
 * actual tablets, use zwp_tablet_manager_v2.get_tablet_seat.
 */
public class ZwpTabletManagerV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletManagerV2> get() = Companion

    /**
     * Get the tablet seat
     *
     * Get the zwp_tablet_seat_v2 object for the given seat. This object
     * provides access to all graphics tablets in this seat.
     */
    public fun getTabletSeat(seat: WlSeat): ZwpTabletSeatV2 {
        val created = connection.createProxy(ZwpTabletSeatV2, this.version)
        request(0).newId(created).obj(seat).end()
        return created
    }

    /**
     * Release the memory for the tablet manager object
     *
     * Destroy the zwp_tablet_manager_v2 object. Objects created from this
     * object are unaffected and should be destroyed separately.
     */
    public fun destroy(): Unit {
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpTabletManagerV2>("zwp_tablet_manager_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletManagerV2 = ZwpTabletManagerV2(connection, id, version)
    }
}
