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
 * Offer to replace the contents of the primary selection
 *
 * The source side of a wp_primary_selection_offer, it provides a way to
 * describe the offered data and respond to requests to transfer the
 * requested contents of the primary selection clipboard.
 */
public class ZwpPrimarySelectionSourceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPrimarySelectionSourceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_primary_selection_source_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Add an offered mime type
         *
         * This request adds a mime type to the set of mime types advertised to
         * targets. Can be called several times to offer multiple types.
         */
        public fun offer(resource: ZwpPrimarySelectionSourceV1, mimeType: String)

        /**
         * Destroy the primary selection source
         *
         * Destroy the primary selection source.
         */
        public fun destroy(resource: ZwpPrimarySelectionSourceV1)
    }

    /**
     * Send the primary selection contents
     *
     * Request for the current primary selection contents from the client.
     * Send the specified mime type over the passed file descriptor, then
     * close it.
     */
    public fun sendSend(mimeType: String, fd: Fd) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "send", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
        event(0).string(mimeType).fd(fd).end()
    }

    /**
     * Request for primary selection contents was canceled
     *
     * This primary selection source is no longer valid. The client should
     * clean up and destroy this primary selection source.
     */
    public fun sendCancelled() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "cancelled", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_primary_selection_source_v1 is not implemented")
        when (opcode) {
            0 -> {
                val mimeType = args.string()
                args.end()
                if (Wire.tracing) Wire.request(this, "offer", "mime_type=${Wire.show(mimeType)}")
                handler.offer(this, mimeType)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_primary_selection_source_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPrimarySelectionSourceV1>("zwp_primary_selection_source_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPrimarySelectionSourceV1 = ZwpPrimarySelectionSourceV1(client, id, version)
    }
}
