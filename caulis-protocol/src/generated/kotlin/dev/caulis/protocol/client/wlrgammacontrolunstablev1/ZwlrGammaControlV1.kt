// Generated from wlr-gamma-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrgammacontrolunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ZwlrGammaControlV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrGammaControlV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Size of gamma ramps
         *
         * Advertise the size of each gamma ramp.
         *
         * This event is sent immediately when the gamma control object is created.
         */
        public fun gammaSize(proxy: ZwlrGammaControlV1, size: Int) {}

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
        public fun failed(proxy: ZwlrGammaControlV1) {}
    }

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
    public fun setGamma(fd: Fd): Unit {
        if (Wire.tracing) Wire.sent(this, "set_gamma", "fd=${Wire.show(fd)}")
        request(0).fd(fd).end()
    }

    /**
     * Destroy this control
     *
     * Destroys the gamma control object. If the object is still valid, this
     * restores the original gamma tables.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** invalid gamma tables */
        public const val INVALID_GAMMA: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val size = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "gamma_size", "size=${Wire.show(size)}")
                handler?.gammaSize(this, size)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "failed", "")
                handler?.failed(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrGammaControlV1>("zwlr_gamma_control_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrGammaControlV1 = ZwlrGammaControlV1(connection, id, version)
    }
}
