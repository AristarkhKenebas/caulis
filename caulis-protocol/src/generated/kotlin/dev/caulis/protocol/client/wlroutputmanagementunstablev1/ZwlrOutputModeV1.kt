// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ZwlrOutputModeV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputModeV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Mode size
         *
         * This event describes the mode size. The size is given in physical
         * hardware units of the output device. This is not necessarily the same as
         * the output size in the global compositor space. For instance, the output
         * may be scaled or transformed.
         */
        public fun size(proxy: ZwlrOutputModeV1, width: Int, height: Int) {}

        /**
         * Mode refresh rate
         *
         * This event describes the mode's fixed vertical refresh rate. It is only
         * sent if the mode has a fixed refresh rate.
         */
        public fun refresh(proxy: ZwlrOutputModeV1, refresh: Int) {}

        /**
         * Mode is preferred
         *
         * This event advertises this mode as preferred.
         */
        public fun preferred(proxy: ZwlrOutputModeV1) {}

        /**
         * The mode has disappeared
         *
         * This event indicates that the mode is no longer available. The mode
         * object becomes inert. Clients should send a destroy request and release
         * any resources associated with it.
         */
        public fun finished(proxy: ZwlrOutputModeV1) {}
    }

    /**
     * Destroy the mode object
     *
     * This request indicates that the client will no longer use this mode
     * object.
     *
     * Since version 3.
     */
    public fun release(): Unit {
        check(version >= 3) { "zwlr_output_mode_v1.release needs version 3, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.size(this, width, height)
            }
            1 -> {
                val refresh = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "refresh", "refresh=${Wire.show(refresh)}")
                handler?.refresh(this, refresh)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "preferred", "")
                handler?.preferred(this)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "finished", "")
                handler?.finished(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrOutputModeV1>("zwlr_output_mode_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputModeV1 = ZwlrOutputModeV1(connection, id, version)
    }
}
