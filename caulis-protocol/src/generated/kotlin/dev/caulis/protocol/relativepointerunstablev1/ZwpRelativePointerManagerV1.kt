// Generated from relative-pointer-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.relativepointerunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlPointer

/**
 * Get relative pointer objects
 *
 * A global interface used for getting the relative pointer object for a
 * given pointer.
 */
public class ZwpRelativePointerManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpRelativePointerManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_relative_pointer_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the relative pointer manager object
         *
         * Used by the client to notify the server that it will no longer use this
         * relative pointer manager object.
         */
        public fun destroy(resource: ZwpRelativePointerManagerV1)

        /**
         * Get a relative pointer object
         *
         * Create a relative pointer interface given a wl_pointer object. See the
         * wp_relative_pointer interface for more details.
         */
        public fun getRelativePointer(resource: ZwpRelativePointerManagerV1, id: ZwpRelativePointerV1, pointer: WlPointer)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_relative_pointer_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val id = client.register(ZwpRelativePointerV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_relative_pointer", "id=${Wire.show(id)}, pointer=${Wire.show(pointer)}")
                handler.getRelativePointer(this, id, pointer)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_relative_pointer_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpRelativePointerManagerV1>("zwp_relative_pointer_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpRelativePointerManagerV1 = ZwpRelativePointerManagerV1(client, id, version)
    }
}
