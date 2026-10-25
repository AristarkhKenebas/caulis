// Generated from wlr-data-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrdatacontrolunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSeat

/**
 * Manager to control data devices
 *
 * This interface is a manager that allows creating per-seat data device
 * controls.
 */
public class ZwlrDataControlManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrDataControlManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_data_control_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a new data source
         *
         * Create a new data source.
         */
        public fun createDataSource(resource: ZwlrDataControlManagerV1, id: ZwlrDataControlSourceV1)

        /**
         * Get a data device for a seat
         *
         * Create a data device that can be used to manage a seat's selection.
         */
        public fun getDataDevice(resource: ZwlrDataControlManagerV1, id: ZwlrDataControlDeviceV1, seat: WlSeat)

        /**
         * Destroy the manager
         *
         * All objects created by the manager will still remain valid, until their
         * appropriate destroy request has been called.
         */
        public fun destroy(resource: ZwlrDataControlManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_data_control_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(ZwlrDataControlSourceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "create_data_source", "id=${Wire.show(id)}")
                handler.createDataSource(this, id)
            }
            1 -> {
                val idId = args.newId()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(ZwlrDataControlDeviceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_data_device", "id=${Wire.show(id)}, seat=${Wire.show(seat)}")
                handler.getDataDevice(this, id, seat)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_data_control_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrDataControlManagerV1>("zwlr_data_control_manager_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrDataControlManagerV1 = ZwlrDataControlManagerV1(client, id, version)
    }
}
