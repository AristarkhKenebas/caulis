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
 * Offer to transfer primary selection contents
 *
 * A wp_primary_selection_offer represents an offer to transfer the contents
 * of the primary selection clipboard to the client. Similar to
 * wl_data_offer, the offer also describes the mime types that the data can
 * be converted to and provides the mechanisms for transferring the data
 * directly to the client.
 */
public class ZwpPrimarySelectionOfferV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPrimarySelectionOfferV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Advertise offered mime type
         *
         * Sent immediately after creating announcing the
         * wp_primary_selection_offer through
         * wp_primary_selection_device.data_offer. One event is sent per offered
         * mime type.
         */
        public fun offer(proxy: ZwpPrimarySelectionOfferV1, mimeType: String) {}
    }

    /**
     * Request that the data is transferred
     *
     * To transfer the contents of the primary selection clipboard, the client
     * issues this request and indicates the mime type that it wants to
     * receive. The transfer happens through the passed file descriptor
     * (typically created with the pipe system call). The source client writes
     * the data in the mime type representation requested and then closes the
     * file descriptor.
     *
     * The receiving client reads from the read end of the pipe until EOF and
     * closes its end, at which point the transfer is complete.
     */
    public fun receive(mimeType: String, fd: Fd): Unit {
        if (Wire.tracing) Wire.sent(this, "receive", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
        request(0).string(mimeType).fd(fd).end()
    }

    /**
     * Destroy the primary selection offer
     *
     * Destroy the primary selection offer.
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
                args.end()
                if (Wire.tracing) Wire.received(this, "offer", "mime_type=${Wire.show(mimeType)}")
                handler?.offer(this, mimeType)
            }
        }
    }

    public companion object : ProxyType<ZwpPrimarySelectionOfferV1>("zwp_primary_selection_offer_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPrimarySelectionOfferV1 = ZwpPrimarySelectionOfferV1(connection, id, version)
    }
}
