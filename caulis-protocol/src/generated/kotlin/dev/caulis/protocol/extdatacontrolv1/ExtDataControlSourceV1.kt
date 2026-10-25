// Generated from ext-data-control-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extdatacontrolv1

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
 * The ext_data_control_source object is the source side of a
 * ext_data_control_offer. It is created by the source client in a data
 * transfer and provides a way to describe the offered data and a way to
 * respond to requests to transfer the data.
 */
public class ExtDataControlSourceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtDataControlSourceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_data_control_source_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Add an offered MIME type
         *
         * This request adds a MIME type to the set of MIME types advertised to
         * targets. Can be called several times to offer multiple types.
         *
         * Calling this after ext_data_control_device.set_selection is a protocol
         * error.
         */
        public fun offer(resource: ExtDataControlSourceV1, mimeType: String)

        /**
         * Destroy this source
         *
         * Destroys the data source object.
         */
        public fun destroy(resource: ExtDataControlSourceV1)
    }

    /**
     * Send the data
     *
     * Request for data from the client. Send the data as the specified MIME
     * type over the passed file descriptor, then close it.
     */
    public fun sendSend(mimeType: String, fd: Fd) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "send", "mime_type=${Wire.show(mimeType)}, fd=${Wire.show(fd)}")
        event(0).string(mimeType).fd(fd).end()
    }

    /**
     * Selection was cancelled
     *
     * This data source is no longer valid. The data source has been replaced
     * by another data source.
     *
     * The client should clean up and destroy this data source.
     */
    public fun sendCancelled() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "cancelled", "")
        event(1).end()
    }

    public object Error {
        /** offer sent after ext_data_control_device.set_selection */
        public const val INVALID_OFFER: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_data_control_source_v1 is not implemented")
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
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_data_control_source_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtDataControlSourceV1>("ext_data_control_source_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtDataControlSourceV1 = ExtDataControlSourceV1(client, id, version)
    }
}
