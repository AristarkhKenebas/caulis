// Generated from wlr-data-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrdatacontrolunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Offer to transfer data
 *
 * A wlr_data_control_offer represents a piece of data offered for transfer
 * by another client (the source client). The offer describes the different
 * MIME types that the data can be converted to and provides the mechanism
 * for transferring the data directly from the source client.
 */
public class ZwlrDataControlOfferV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrDataControlOfferV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Advertise offered MIME type
         *
         * Sent immediately after creating the wlr_data_control_offer object.
         * One event per offered MIME type.
         */
        public fun offer(proxy: ZwlrDataControlOfferV1, mimeType: String) {}
    }

    /**
     * Request that the data is transferred
     *
     * To transfer the offered data, the client issues this request and
     * indicates the MIME type it wants to receive. The transfer happens
     * through the passed file descriptor (typically created with the pipe
     * system call). The source client writes the data in the MIME type
     * representation requested and then closes the file descriptor.
     *
     * The receiving client reads from the read end of the pipe until EOF and
     * then closes its end, at which point the transfer is complete.
     *
     * This request may happen multiple times for different MIME types.
     */
    public fun receive(mimeType: String, fd: Fd): Unit {
        if (Wire.tracing) Wire.sent(this, "receive", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
        request(0).string(mimeType).fd(fd).end()
    }

    /**
     * Destroy this offer
     *
     * Destroys the data offer object.
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

    public companion object : ProxyType<ZwlrDataControlOfferV1>("zwlr_data_control_offer_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrDataControlOfferV1 = ZwlrDataControlOfferV1(connection, id, version)
    }
}
