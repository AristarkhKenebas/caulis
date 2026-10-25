// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wpprimaryselectionunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat

/**
 * X primary selection emulation
 *
 * The primary selection device manager is a singleton global object that
 * provides access to the primary selection. It allows to create
 * wp_primary_selection_source objects, as well as retrieving the per-seat
 * wp_primary_selection_device objects.
 */
public class ZwpPrimarySelectionDeviceManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPrimarySelectionDeviceManagerV1> get() = Companion

    /**
     * Create a new primary selection source
     *
     * Create a new primary selection source.
     */
    public fun createSource(): ZwpPrimarySelectionSourceV1 {
        val created = connection.createProxy(ZwpPrimarySelectionSourceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_source", "id=${Wire.show(created)}")
        request(0).newId(created).end()
        return created
    }

    /**
     * Create a new primary selection device
     *
     * Create a new data device for a given seat.
     */
    public fun getDevice(seat: WlSeat): ZwpPrimarySelectionDeviceV1 {
        val created = connection.createProxy(ZwpPrimarySelectionDeviceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_device", "id=${Wire.show(created)}, seat=${Wire.show(seat)}")
        request(1).newId(created).obj(seat).end()
        return created
    }

    /**
     * Destroy the primary selection device manager
     *
     * Destroy the primary selection device manager.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpPrimarySelectionDeviceManagerV1>("zwp_primary_selection_device_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPrimarySelectionDeviceManagerV1 = ZwpPrimarySelectionDeviceManagerV1(connection, id, version)
    }
}
