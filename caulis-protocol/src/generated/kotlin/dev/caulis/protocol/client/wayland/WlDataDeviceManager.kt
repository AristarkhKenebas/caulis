// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Data transfer interface
 *
 * The wl_data_device_manager is a singleton global object that
 * provides access to inter-client data transfer mechanisms such as
 * copy-and-paste and drag-and-drop.  These mechanisms are tied to
 * a wl_seat and this interface lets a client get a wl_data_device
 * corresponding to a wl_seat.
 *
 * Depending on the version bound, the objects created from the bound
 * wl_data_device_manager object will have different requirements for
 * functioning properly. See wl_data_source.set_actions,
 * wl_data_offer.accept and wl_data_offer.finish for details.
 */
public class WlDataDeviceManager private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlDataDeviceManager> get() = Companion

    /**
     * Create a new data source
     *
     * Create a new data source.
     */
    public fun createDataSource(): WlDataSource {
        val created = connection.createProxy(WlDataSource, this.version)
        if (Wire.tracing) Wire.sent(this, "create_data_source", "id=${Wire.show(created)}")
        request(0).newId(created).end()
        return created
    }

    /**
     * Create a new data device
     *
     * Create a new data device for a given seat.
     */
    public fun getDataDevice(seat: WlSeat): WlDataDevice {
        val created = connection.createProxy(WlDataDevice, this.version)
        if (Wire.tracing) Wire.sent(this, "get_data_device", "id=${Wire.show(created)}, seat=${Wire.show(seat)}")
        request(1).newId(created).obj(seat).end()
        return created
    }

    /**
     * Destroy wl_data_device_manager
     *
     * This request destroys the wl_data_device_manager. This has no effect on any other
     * objects.
     *
     * Since version 4.
     */
    public fun release(): Unit {
        check(version >= 4) { "wl_data_device_manager.release needs version 4, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    /** drag and drop actions */
    public object DndAction {
        /** no action */
        public const val NONE: Int = 0
        /** copy action */
        public const val COPY: Int = 1
        /** move action */
        public const val MOVE: Int = 2
        /** ask action */
        public const val ASK: Int = 4
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlDataDeviceManager>("wl_data_device_manager", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlDataDeviceManager = WlDataDeviceManager(connection, id, version)
    }
}
