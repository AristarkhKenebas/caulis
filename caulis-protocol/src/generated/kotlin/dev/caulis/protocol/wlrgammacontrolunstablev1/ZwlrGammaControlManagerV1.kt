// Generated from wlr-gamma-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrgammacontrolunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput

/**
 * Manager to create per-output gamma controls
 *
 * This interface is a manager that allows creating per-output gamma
 * controls.
 */
public class ZwlrGammaControlManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrGammaControlManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_gamma_control_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Get a gamma control for an output
         *
         * Create a gamma control that can be used to adjust gamma tables for the
         * provided output.
         */
        public fun getGammaControl(resource: ZwlrGammaControlManagerV1, id: ZwlrGammaControlV1, output: WlOutput)

        /**
         * Destroy the manager
         *
         * All objects created by the manager will still remain valid, until their
         * appropriate destroy request has been called.
         */
        public fun destroy(resource: ZwlrGammaControlManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_gamma_control_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val output = args.obj(WlOutput::class.java)
                args.end()
                val id = client.register(ZwlrGammaControlV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_gamma_control", "id=${Wire.show(id)}, output=${Wire.show(output)}")
                handler.getGammaControl(this, id, output)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_gamma_control_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrGammaControlManagerV1>("zwlr_gamma_control_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrGammaControlManagerV1 = ZwlrGammaControlManagerV1(client, id, version)
    }
}
