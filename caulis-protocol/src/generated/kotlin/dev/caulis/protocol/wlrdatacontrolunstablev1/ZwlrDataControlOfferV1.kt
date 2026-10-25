// Generated from wlr-data-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrdatacontrolunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Offer to transfer data
 *
 * A wlr_data_control_offer represents a piece of data offered for transfer
 * by another client (the source client). The offer describes the different
 * MIME types that the data can be converted to and provides the mechanism
 * for transferring the data directly from the source client.
 */
public class ZwlrDataControlOfferV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrDataControlOfferV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_data_control_offer_v1. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun receive(resource: ZwlrDataControlOfferV1, mimeType: String, fd: Fd)

        /**
         * Destroy this offer
         *
         * Destroys the data offer object.
         */
        public fun destroy(resource: ZwlrDataControlOfferV1)
    }

    /**
     * Advertise offered MIME type
     *
     * Sent immediately after creating the wlr_data_control_offer object.
     * One event per offered MIME type.
     */
    public fun sendOffer(mimeType: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "offer", "mime_type=${Wire.show(mimeType)}")
        event(0).string(mimeType).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_data_control_offer_v1 is not implemented")
        when (opcode) {
            0 -> {
                val mimeType = args.string()
                val fd = args.fd()
                args.end()
                if (Wire.tracing) Wire.request(this, "receive", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
                handler.receive(this, mimeType, fd)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_data_control_offer_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrDataControlOfferV1>("zwlr_data_control_offer_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrDataControlOfferV1 = ZwlrDataControlOfferV1(client, id, version)
    }
}
