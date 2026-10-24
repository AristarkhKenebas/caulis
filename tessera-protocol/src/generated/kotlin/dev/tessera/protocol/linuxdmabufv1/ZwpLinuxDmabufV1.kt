// Generated from linux-dmabuf-v1.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.linuxdmabufv1

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType
import dev.tessera.protocol.wayland.WlDisplay
import dev.tessera.protocol.wayland.WlSurface

/**
 * Factory for creating dmabuf-based wl_buffers
 *
 * This interface offers ways to create generic dmabuf-based wl_buffers.
 *
 * For more information about dmabuf, see:
 * https://www.kernel.org/doc/html/next/userspace-api/dma-buf-alloc-exchange.html
 *
 * Clients can use the get_surface_feedback request to get dmabuf feedback
 * for a particular surface. If the client wants to retrieve feedback not
 * tied to a surface, they can use the get_default_feedback request.
 *
 * The following are required from clients:
 *
 * - Clients must ensure that either all data in the dma-buf is
 *   coherent for all subsequent read access or that coherency is
 *   correctly handled by the underlying kernel-side dma-buf
 *   implementation.
 *
 * - Don't make any more attachments after sending the buffer to the
 *   compositor. Making more attachments later increases the risk of
 *   the compositor not being able to use (re-import) an existing
 *   dmabuf-based wl_buffer.
 *
 * The underlying graphics stack must ensure the following:
 *
 * - The dmabuf file descriptors relayed to the server will stay valid
 *   for the whole lifetime of the wl_buffer. This means the server may
 *   at any time use those fds to import the dmabuf into any kernel
 *   sub-system that might accept it.
 *
 * However, when the underlying graphics stack fails to deliver the
 * promise, because of e.g. a device hot-unplug which raises internal
 * errors, after the wl_buffer has been successfully created the
 * compositor must not raise protocol errors to the client when dmabuf
 * import later fails.
 *
 * To create a wl_buffer from one or more dmabufs, a client creates a
 * zwp_linux_buffer_params_v1 object with a zwp_linux_dmabuf_v1.create_params
 * request. All planes required by the intended format are added with
 * the 'add' request. Finally, a 'create' or 'create_immed' request is
 * issued, which has the following outcome depending on the import success.
 *
 * The 'create' request,
 * - on success, triggers a 'created' event which provides the final
 *   wl_buffer to the client.
 * - on failure, triggers a 'failed' event to convey that the server
 *   cannot use the dmabufs received from the client.
 *
 * For the 'create_immed' request,
 * - on success, the server immediately imports the added dmabufs to
 *   create a wl_buffer. No event is sent from the server in this case.
 * - on failure, the server can choose to either:
 *   - terminate the client by raising a fatal error.
 *   - mark the wl_buffer as failed, and send a 'failed' event to the
 *     client. If the client uses a failed wl_buffer as an argument to any
 *     request, the behaviour is compositor implementation-defined.
 *
 * For all DRM formats and unless specified in another protocol extension,
 * pre-multiplied alpha is used for pixel values.
 *
 * Unless specified otherwise in another protocol extension, implicit
 * synchronization is used. In other words, compositors and clients must
 * wait and signal fences implicitly passed via the DMA-BUF's reservation
 * mechanism.
 */
public class ZwpLinuxDmabufV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpLinuxDmabufV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_linux_dmabuf_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Unbind the factory
         *
         * Objects created through this interface, especially wl_buffers, will
         * remain valid.
         */
        public fun destroy(resource: ZwpLinuxDmabufV1)

        /**
         * Create a temporary object for buffer parameters
         *
         * This temporary object is used to collect multiple dmabuf handles into
         * a single batch to create a wl_buffer. It can only be used once and
         * should be destroyed after a 'created' or 'failed' event has been
         * received.
         */
        public fun createParams(resource: ZwpLinuxDmabufV1, paramsId: ZwpLinuxBufferParamsV1)

        /**
         * Get default feedback
         *
         * This request creates a new zwp_linux_dmabuf_feedback_v1 object not bound
         * to a particular surface. This object will deliver feedback about dmabuf
         * parameters to use if the client doesn't support per-surface feedback
         * (see get_surface_feedback).
         *
         * Since version 4.
         */
        public fun getDefaultFeedback(resource: ZwpLinuxDmabufV1, id: ZwpLinuxDmabufFeedbackV1)

        /**
         * Get feedback for a surface
         *
         * This request creates a new zwp_linux_dmabuf_feedback_v1 object for the
         * specified wl_surface. This object will deliver feedback about dmabuf
         * parameters to use for buffers attached to this surface.
         *
         * If the surface is destroyed before the zwp_linux_dmabuf_feedback_v1 object,
         * the feedback object becomes inert.
         *
         * Since version 4.
         */
        public fun getSurfaceFeedback(resource: ZwpLinuxDmabufV1, id: ZwpLinuxDmabufFeedbackV1, surface: WlSurface)
    }

    /**
     * Supported buffer format
     *
     * This event advertises one buffer format that the server supports.
     * All the supported formats are advertised once when the client
     * binds to this interface. A roundtrip after binding guarantees
     * that the client has received all supported formats.
     *
     * For the definition of the format codes, see the
     * zwp_linux_buffer_params_v1::create request.
     *
     * Starting version 4, the format event is deprecated and must not be
     * sent by compositors. Instead, use get_default_feedback or
     * get_surface_feedback.
     */
    public fun sendFormat(format: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(format).end()
    }

    /**
     * Supported buffer format modifier
     *
     * This event advertises the formats that the server supports, along with
     * the modifiers supported for each format. All the supported modifiers
     * for all the supported formats are advertised once when the client
     * binds to this interface. A roundtrip after binding guarantees that
     * the client has received all supported format-modifier pairs.
     *
     * For legacy support, DRM_FORMAT_MOD_INVALID (that is, modifier_hi ==
     * 0x00ffffff and modifier_lo == 0xffffffff) is allowed in this event.
     * It indicates that the server can support the format with an implicit
     * modifier. When a plane has DRM_FORMAT_MOD_INVALID as its modifier, it
     * is as if no explicit modifier is specified. The effective modifier
     * will be derived from the dmabuf.
     *
     * A compositor that sends valid modifiers and DRM_FORMAT_MOD_INVALID for
     * a given format supports both explicit modifiers and implicit modifiers.
     *
     * For the definition of the format and modifier codes, see the
     * zwp_linux_buffer_params_v1::create and zwp_linux_buffer_params_v1::add
     * requests.
     *
     * Starting version 4, the modifier event is deprecated and must not be
     * sent by compositors. Instead, use get_default_feedback or
     * get_surface_feedback.
     *
     * Since version 3.
     */
    public fun sendModifier(format: Int, modifierHi: Int, modifierLo: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(1).uint(format).uint(modifierHi).uint(modifierLo).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_linux_dmabuf_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val paramsIdId = args.newId()
                args.end()
                val paramsId = client.register(ZwpLinuxBufferParamsV1.create(client, paramsIdId, version))
                handler.createParams(this, paramsId)
            }
            2 -> {
                if (version < 4) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_linux_dmabuf_v1.get_default_feedback needs version 4")
                val idId = args.newId()
                args.end()
                val id = client.register(ZwpLinuxDmabufFeedbackV1.create(client, idId, version))
                handler.getDefaultFeedback(this, id)
            }
            3 -> {
                if (version < 4) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwp_linux_dmabuf_v1.get_surface_feedback needs version 4")
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(ZwpLinuxDmabufFeedbackV1.create(client, idId, version))
                handler.getSurfaceFeedback(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_linux_dmabuf_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpLinuxDmabufV1>("zwp_linux_dmabuf_v1", 6) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpLinuxDmabufV1 = ZwpLinuxDmabufV1(client, id, version)
    }
}
