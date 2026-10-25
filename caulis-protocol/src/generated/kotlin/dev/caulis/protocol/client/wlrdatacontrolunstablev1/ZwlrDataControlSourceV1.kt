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
 * The wlr_data_control_source object is the source side of a
 * wlr_data_control_offer. It is created by the source client in a data
 * transfer and provides a way to describe the offered data and a way to
 * respond to requests to transfer the data.
 */
public class ZwlrDataControlSourceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrDataControlSourceV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Send the data
         *
         * Request for data from the client. Send the data as the specified MIME
         * type over the passed file descriptor, then close it.
         */
        public fun send(proxy: ZwlrDataControlSourceV1, mimeType: String, fd: Fd) {}

        /**
         * Selection was cancelled
         *
         * This data source is no longer valid. The data source has been replaced
         * by another data source.
         *
         * The client should clean up and destroy this data source.
         */
        public fun cancelled(proxy: ZwlrDataControlSourceV1) {}
    }

    /**
     * Add an offered MIME type
     *
     * This request adds a MIME type to the set of MIME types advertised to
     * targets. Can be called several times to offer multiple types.
     *
     * Calling this after wlr_data_control_device.set_selection is a protocol
     * error.
     */
    public fun offer(mimeType: String): Unit {
        if (Wire.tracing) Wire.sent(this, "offer", "mime_type=${Wire.show(mimeType)}")
        request(0).string(mimeType).end()
    }

    /**
     * Destroy this source
     *
     * Destroys the data source object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** offer sent after wlr_data_control_device.set_selection */
        public const val INVALID_OFFER: Int = 1
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

    public companion object : ProxyType<ZwlrDataControlSourceV1>("zwlr_data_control_source_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrDataControlSourceV1 = ZwlrDataControlSourceV1(connection, id, version)
    }
}
