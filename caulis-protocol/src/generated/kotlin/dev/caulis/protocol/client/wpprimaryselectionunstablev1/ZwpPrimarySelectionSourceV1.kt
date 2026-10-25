// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wpprimaryselectionunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Offer to replace the contents of the primary selection
 *
 * The source side of a wp_primary_selection_offer, it provides a way to
 * describe the offered data and respond to requests to transfer the
 * requested contents of the primary selection clipboard.
 */
public class ZwpPrimarySelectionSourceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPrimarySelectionSourceV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Send the primary selection contents
         *
         * Request for the current primary selection contents from the client.
         * Send the specified mime type over the passed file descriptor, then
         * close it.
         */
        public fun send(proxy: ZwpPrimarySelectionSourceV1, mimeType: String, fd: Fd) {}

        /**
         * Request for primary selection contents was canceled
         *
         * This primary selection source is no longer valid. The client should
         * clean up and destroy this primary selection source.
         */
        public fun cancelled(proxy: ZwpPrimarySelectionSourceV1) {}
    }

    /**
     * Add an offered mime type
     *
     * This request adds a mime type to the set of mime types advertised to
     * targets. Can be called several times to offer multiple types.
     */
    public fun offer(mimeType: String): Unit {
        if (Wire.tracing) Wire.sent(this, "offer", "mime_type=${Wire.show(mimeType)}")
        request(0).string(mimeType).end()
    }

    /**
     * Destroy the primary selection source
     *
     * Destroy the primary selection source.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val mimeType = args.string()
                val fd = args.fd()
                args.end()
                if (Wire.tracing) Wire.received(this, "send", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
                handler?.send(this, mimeType, fd)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "cancelled", "")
                handler?.cancelled(this)
            }
        }
    }

    public companion object : ProxyType<ZwpPrimarySelectionSourceV1>("zwp_primary_selection_source_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPrimarySelectionSourceV1 = ZwpPrimarySelectionSourceV1(connection, id, version)
    }
}
