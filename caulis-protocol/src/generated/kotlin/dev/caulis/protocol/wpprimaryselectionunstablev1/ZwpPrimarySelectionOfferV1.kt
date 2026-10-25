// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wpprimaryselectionunstablev1

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Offer to transfer primary selection contents
 *
 * A wp_primary_selection_offer represents an offer to transfer the contents
 * of the primary selection clipboard to the client. Similar to
 * wl_data_offer, the offer also describes the mime types that the data can
 * be converted to and provides the mechanisms for transferring the data
 * directly to the client.
 */
public class ZwpPrimarySelectionOfferV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPrimarySelectionOfferV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_primary_selection_offer_v1. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun receive(resource: ZwpPrimarySelectionOfferV1, mimeType: String, fd: Fd)

        /**
         * Destroy the primary selection offer
         *
         * Destroy the primary selection offer.
         */
        public fun destroy(resource: ZwpPrimarySelectionOfferV1)
    }

    /**
     * Advertise offered mime type
     *
     * Sent immediately after creating announcing the
     * wp_primary_selection_offer through
     * wp_primary_selection_device.data_offer. One event is sent per offered
     * mime type.
     */
    public fun sendOffer(mimeType: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "offer", "mime_type=${Wire.show(mimeType)}")
        event(0).string(mimeType).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_primary_selection_offer_v1 is not implemented")
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
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_primary_selection_offer_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPrimarySelectionOfferV1>("zwp_primary_selection_offer_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPrimarySelectionOfferV1 = ZwpPrimarySelectionOfferV1(client, id, version)
    }
}
