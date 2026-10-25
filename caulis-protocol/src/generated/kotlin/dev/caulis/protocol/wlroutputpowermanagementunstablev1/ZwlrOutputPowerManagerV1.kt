// Generated from wlr-output-power-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlroutputpowermanagementunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput

/**
 * Manager to create per-output power management
 *
 * This interface is a manager that allows creating per-output power
 * management mode controls.
 */
public class ZwlrOutputPowerManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputPowerManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_power_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Get a power management for an output
         *
         * Create an output power management mode control that can be used to
         * adjust the power management mode for a given output.
         */
        public fun getOutputPower(resource: ZwlrOutputPowerManagerV1, id: ZwlrOutputPowerV1, output: WlOutput)

        /**
         * Destroy the manager
         *
         * All objects created by the manager will still remain valid, until their
         * appropriate destroy request has been called.
         */
        public fun destroy(resource: ZwlrOutputPowerManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_power_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val output = args.obj(WlOutput::class.java)
                args.end()
                val id = client.register(ZwlrOutputPowerV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_output_power", "id=${Wire.show(id)}, output=${Wire.show(output)}")
                handler.getOutputPower(this, id, output)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_power_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputPowerManagerV1>("zwlr_output_power_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputPowerManagerV1 = ZwlrOutputPowerManagerV1(client, id, version)
    }
}
