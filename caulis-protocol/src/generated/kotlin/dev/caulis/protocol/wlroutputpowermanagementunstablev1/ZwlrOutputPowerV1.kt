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

/**
 * Adjust power management mode for an output
 *
 * This object offers requests to set the power management mode of
 * an output.
 */
public class ZwlrOutputPowerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputPowerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_power_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set an outputs power save mode
         *
         * Set an output's power save mode to the given mode. The mode change
         * is effective immediately. If the output does not support the given
         * mode a failed event is sent.
         */
        public fun setMode(resource: ZwlrOutputPowerV1, mode: Int)

        /**
         * Destroy this power management
         *
         * Destroys the output power management mode control object.
         */
        public fun destroy(resource: ZwlrOutputPowerV1)
    }

    /**
     * Report a power management mode change
     *
     * Report the power management mode change of an output.
     *
     * The mode event is sent after an output changed its power
     * management mode. The reason can be a client using set_mode or the
     * compositor deciding to change an output's mode.
     * This event is also sent immediately when the object is created
     * so the client is informed about the current power management mode.
     */
    public fun sendMode(mode: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "mode", "mode=${Wire.show(mode)}")
        event(0).uint(mode).end()
    }

    /**
     * Object no longer valid
     *
     * This event indicates that the output power management mode control
     * is no longer valid. This can happen for a number of reasons,
     * including:
     * - The output doesn't support power management
     * - Another client already has exclusive power management mode control
     *   for this output
     * - The output disappeared
     *
     * Upon receiving this event, the client should destroy this object.
     */
    public fun sendFailed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "")
        event(1).end()
    }

    public object Mode {
        /** Output is turned off. */
        public const val OFF: Int = 0
        /** Output is turned on, no power saving */
        public const val ON: Int = 1
    }

    public object Error {
        /** nonexistent power save mode */
        public const val INVALID_MODE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_power_v1 is not implemented")
        when (opcode) {
            0 -> {
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_mode", "mode=${Wire.show(mode)}")
                handler.setMode(this, mode)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_power_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputPowerV1>("zwlr_output_power_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputPowerV1 = ZwlrOutputPowerV1(client, id, version)
    }
}
