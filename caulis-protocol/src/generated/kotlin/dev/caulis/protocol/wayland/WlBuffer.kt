// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire

/**
 * Content for a wl_surface
 *
 * A buffer provides the content for a wl_surface. Buffers are
 * created through factory interfaces such as wl_shm, wp_linux_buffer_params
 * (from the linux-dmabuf protocol extension) or similar. It has a width and
 * a height and can be attached to a wl_surface, but the mechanism by which a
 * client provides and updates the contents is defined by the buffer factory
 * interface.
 *
 * Color channels are assumed to be electrical rather than optical (in other
 * words, encoded with a transfer function) unless otherwise specified. If
 * the buffer uses a format that has an alpha channel, the alpha channel is
 * assumed to be premultiplied into the electrical color channel values
 * (after transfer function encoding) unless otherwise specified.
 *
 * Note, because wl_buffer objects are created from multiple independent
 * factory interfaces, the wl_buffer interface is frozen at version 1.
 */
public class WlBuffer private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlBuffer> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_buffer. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy a buffer
         *
         * Destroy a buffer. If and how you need to release the backing
         * storage is defined by the buffer factory interface.
         *
         * For possible side-effects to a surface, see wl_surface.attach.
         */
        public fun destroy(resource: WlBuffer)
    }

    /**
     * Compositor releases buffer
     *
     * Sent when this wl_buffer is no longer used by the compositor.
     *
     * For more information on when release events may or may not be sent,
     * and what consequences it has, please see the description of
     * wl_surface.attach.
     *
     * If a client receives a release event before the frame callback
     * requested in the same wl_surface.commit that attaches this
     * wl_buffer to a surface, then the client is immediately free to
     * reuse the buffer and its backing storage, and does not need a
     * second buffer for the next surface content update. Typically
     * this is possible, when the compositor maintains a copy of the
     * wl_surface contents, e.g. as a GL texture. This is an important
     * optimization for GL(ES) compositors with wl_shm clients.
     */
    public fun sendRelease() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "release", "")
        event(0).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_buffer is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_buffer request $opcode")
        }
    }

    public companion object : ResourceType<WlBuffer>("wl_buffer", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlBuffer = WlBuffer(client, id, version)
    }
}
