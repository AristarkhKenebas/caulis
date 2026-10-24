// Generated from xdg-output-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgoutputunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput

/**
 * Manage xdg_output objects
 *
 * A global factory interface for xdg_output objects.
 */
public class ZxdgOutputManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZxdgOutputManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zxdg_output_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the xdg_output_manager object
         *
         * Using this request a client can tell the server that it is not
         * going to use the xdg_output_manager object anymore.
         *
         * Any objects already created through this instance are not affected.
         */
        public fun destroy(resource: ZxdgOutputManagerV1)

        /**
         * Create an xdg output from a wl_output
         *
         * This creates a new xdg_output object for the given wl_output.
         */
        public fun getXdgOutput(resource: ZxdgOutputManagerV1, id: ZxdgOutputV1, output: WlOutput)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zxdg_output_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val output = args.obj(WlOutput::class.java)
                args.end()
                val id = client.register(ZxdgOutputV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_xdg_output", "id=${Wire.show(id)}, output=${Wire.show(output)}")
                handler.getXdgOutput(this, id, output)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zxdg_output_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZxdgOutputManagerV1>("zxdg_output_manager_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZxdgOutputManagerV1 = ZxdgOutputManagerV1(client, id, version)
    }
}
