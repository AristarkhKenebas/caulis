// Generated from ext-data-control-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extdatacontrolv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat

/**
 * Manager to control data devices
 *
 * This interface is a manager that allows creating per-seat data device
 * controls.
 */
public class ExtDataControlManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtDataControlManagerV1> get() = Companion

    /**
     * Create a new data source
     *
     * Create a new data source.
     */
    public fun createDataSource(): ExtDataControlSourceV1 {
        val created = connection.createProxy(ExtDataControlSourceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_data_source", "id=${Wire.show(created)}")
        request(0).newId(created).end()
        return created
    }

    /**
     * Get a data device for a seat
     *
     * Create a data device that can be used to manage a seat's selection.
     */
    public fun getDataDevice(seat: WlSeat): ExtDataControlDeviceV1 {
        val created = connection.createProxy(ExtDataControlDeviceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_data_device", "id=${Wire.show(created)}, seat=${Wire.show(seat)}")
        request(1).newId(created).obj(seat).end()
        return created
    }

    /**
     * Destroy the manager
     *
     * All objects created by the manager will still remain valid, until their
     * appropriate destroy request has been called.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtDataControlManagerV1>("ext_data_control_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtDataControlManagerV1 = ExtDataControlManagerV1(connection, id, version)
    }
}
