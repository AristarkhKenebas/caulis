// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

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
public class WlDataDeviceManager private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlDataDeviceManager> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_data_device_manager. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a new data source
         *
         * Create a new data source.
         */
        public fun createDataSource(resource: WlDataDeviceManager, id: WlDataSource)

        /**
         * Create a new data device
         *
         * Create a new data device for a given seat.
         */
        public fun getDataDevice(resource: WlDataDeviceManager, id: WlDataDevice, seat: WlSeat)

        /**
         * Destroy wl_data_device_manager
         *
         * This request destroys the wl_data_device_manager. This has no effect on any other
         * objects.
         *
         * Since version 4.
         */
        public fun release(resource: WlDataDeviceManager)
    }

    /**
     * Drag and drop actions
     *
     * Bit flags: combine with `or`.
     */
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

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_data_device_manager is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlDataSource.create(client, idId, version))
                handler.createDataSource(this, id)
            }
            1 -> {
                val idId = args.newId()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(WlDataDevice.create(client, idId, version))
                handler.getDataDevice(this, id, seat)
            }
            2 -> {
                if (version < 4) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_data_device_manager.release needs version 4")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_data_device_manager request $opcode")
        }
    }

    public companion object : ResourceType<WlDataDeviceManager>("wl_data_device_manager", 4) {
        internal override fun create(client: Client, id: Int, version: Int): WlDataDeviceManager = WlDataDeviceManager(client, id, version)
    }
}
