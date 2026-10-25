// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlroutputmanagementunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Output mode
 *
 * This object describes an output mode.
 *
 * Some heads don't support output modes, in which case modes won't be
 * advertised.
 *
 * Properties sent via this interface are applied atomically via the
 * wlr_output_manager.done event. No guarantees are made regarding the order
 * in which properties are sent.
 */
public class ZwlrOutputModeV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputModeV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_mode_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the mode object
         *
         * This request indicates that the client will no longer use this mode
         * object.
         *
         * Since version 3.
         */
        public fun release(resource: ZwlrOutputModeV1)
    }

    /**
     * Mode size
     *
     * This event describes the mode size. The size is given in physical
     * hardware units of the output device. This is not necessarily the same as
     * the output size in the global compositor space. For instance, the output
     * may be scaled or transformed.
     */
    public fun sendSize(width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(0).int(width).int(height).end()
    }

    /**
     * Mode refresh rate
     *
     * This event describes the mode's fixed vertical refresh rate. It is only
     * sent if the mode has a fixed refresh rate.
     */
    public fun sendRefresh(refresh: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "refresh", "refresh=${Wire.show(refresh)}")
        event(1).int(refresh).end()
    }

    /**
     * Mode is preferred
     *
     * This event advertises this mode as preferred.
     */
    public fun sendPreferred() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "preferred", "")
        event(2).end()
    }

    /**
     * The mode has disappeared
     *
     * This event indicates that the mode is no longer available. The mode
     * object becomes inert. Clients should send a destroy request and release
     * any resources associated with it.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(3).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_mode_v1 is not implemented")
        when (opcode) {
            0 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_output_mode_v1.release needs version 3")
                args.end()
                if (Wire.tracing) Wire.request(this, "release", "")
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_mode_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputModeV1>("zwlr_output_mode_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputModeV1 = ZwlrOutputModeV1(client, id, version)
    }
}
