// Generated from wlr-gamma-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrgammacontrolunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Adjust gamma tables for an output
 *
 * This interface allows a client to adjust gamma tables for a particular
 * output.
 *
 * The client will receive the gamma size, and will then be able to set gamma
 * tables. At any time the compositor can send a failed event indicating that
 * this object is no longer valid.
 *
 * There can only be at most one gamma control object per output, which
 * has exclusive access to this particular output. When the gamma control
 * object is destroyed, the gamma table is restored to its original value.
 */
public class ZwlrGammaControlV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrGammaControlV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_gamma_control_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set the gamma table
         *
         * Set the gamma table. The file descriptor can be memory-mapped to provide
         * the raw gamma table, which contains successive gamma ramps for the red,
         * green and blue channels. Each gamma ramp is an array of 16-byte unsigned
         * integers which has the same length as the gamma size.
         *
         * The file descriptor data must have the same length as three times the
         * gamma size.
         */
        public fun setGamma(resource: ZwlrGammaControlV1, fd: Fd)

        /**
         * Destroy this control
         *
         * Destroys the gamma control object. If the object is still valid, this
         * restores the original gamma tables.
         */
        public fun destroy(resource: ZwlrGammaControlV1)
    }

    /**
     * Size of gamma ramps
     *
     * Advertise the size of each gamma ramp.
     *
     * This event is sent immediately when the gamma control object is created.
     */
    public fun sendGammaSize(size: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "gamma_size", "size=${Wire.show(size)}")
        event(0).uint(size).end()
    }

    /**
     * Object no longer valid
     *
     * This event indicates that the gamma control is no longer valid. This
     * can happen for a number of reasons, including:
     * - The output doesn't support gamma tables
     * - Setting the gamma tables failed
     * - Another client already has exclusive gamma control for this output
     * - The compositor has transferred gamma control to another client
     *
     * Upon receiving this event, the client should destroy this object.
     */
    public fun sendFailed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "")
        event(1).end()
    }

    public object Error {
        /** invalid gamma tables */
        public const val INVALID_GAMMA: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_gamma_control_v1 is not implemented")
        when (opcode) {
            0 -> {
                val fd = args.fd()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_gamma", "fd=${Wire.show(fd)}")
                handler.setGamma(this, fd)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_gamma_control_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrGammaControlV1>("zwlr_gamma_control_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrGammaControlV1 = ZwlrGammaControlV1(client, id, version)
    }
}
