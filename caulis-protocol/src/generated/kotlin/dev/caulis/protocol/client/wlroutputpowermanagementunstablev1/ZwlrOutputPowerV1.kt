// Generated from wlr-output-power-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputpowermanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Adjust power management mode for an output
 *
 * This object offers requests to set the power management mode of
 * an output.
 */
public class ZwlrOutputPowerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputPowerV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun mode(proxy: ZwlrOutputPowerV1, mode: Int) {}

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
        public fun failed(proxy: ZwlrOutputPowerV1) {}
    }

    /**
     * Set an outputs power save mode
     *
     * Set an output's power save mode to the given mode. The mode change
     * is effective immediately. If the output does not support the given
     * mode a failed event is sent.
     */
    public fun setMode(mode: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_mode", "mode=${Wire.show(mode)}")
        request(0).uint(mode).end()
    }

    /**
     * Destroy this power management
     *
     * Destroys the output power management mode control object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "mode", "mode=${Wire.show(mode)}")
                handler?.mode(this, mode)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "failed", "")
                handler?.failed(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrOutputPowerV1>("zwlr_output_power_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputPowerV1 = ZwlrOutputPowerV1(connection, id, version)
    }
}
