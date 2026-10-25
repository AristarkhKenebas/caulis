// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wpprimaryselectionunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSeat

/**
 * X primary selection emulation
 *
 * The primary selection device manager is a singleton global object that
 * provides access to the primary selection. It allows to create
 * wp_primary_selection_source objects, as well as retrieving the per-seat
 * wp_primary_selection_device objects.
 */
public class ZwpPrimarySelectionDeviceManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPrimarySelectionDeviceManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_primary_selection_device_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a new primary selection source
         *
         * Create a new primary selection source.
         */
        public fun createSource(resource: ZwpPrimarySelectionDeviceManagerV1, id: ZwpPrimarySelectionSourceV1)

        /**
         * Create a new primary selection device
         *
         * Create a new data device for a given seat.
         */
        public fun getDevice(resource: ZwpPrimarySelectionDeviceManagerV1, id: ZwpPrimarySelectionDeviceV1, seat: WlSeat)

        /**
         * Destroy the primary selection device manager
         *
         * Destroy the primary selection device manager.
         */
        public fun destroy(resource: ZwpPrimarySelectionDeviceManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_primary_selection_device_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(ZwpPrimarySelectionSourceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "create_source", "id=${Wire.show(id)}")
                handler.createSource(this, id)
            }
            1 -> {
                val idId = args.newId()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(ZwpPrimarySelectionDeviceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_device", "id=${Wire.show(id)}, seat=${Wire.show(seat)}")
                handler.getDevice(this, id, seat)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_primary_selection_device_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPrimarySelectionDeviceManagerV1>("zwp_primary_selection_device_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPrimarySelectionDeviceManagerV1 = ZwpPrimarySelectionDeviceManagerV1(client, id, version)
    }
}
