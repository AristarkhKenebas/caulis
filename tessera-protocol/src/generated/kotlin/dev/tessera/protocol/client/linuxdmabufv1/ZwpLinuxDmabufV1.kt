// Generated from linux-dmabuf-v1.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.linuxdmabufv1

import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType
import dev.tessera.protocol.client.wayland.WlSurface

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
public class ZwpLinuxDmabufV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpLinuxDmabufV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun format(proxy: ZwpLinuxDmabufV1, format: Int) {}

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
        public fun modifier(proxy: ZwpLinuxDmabufV1, format: Int, modifierHi: Int, modifierLo: Int) {}
    }

    /**
     * Unbind the factory
     *
     * Objects created through this interface, especially wl_buffers, will
     * remain valid.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a temporary object for buffer parameters
     *
     * This temporary object is used to collect multiple dmabuf handles into
     * a single batch to create a wl_buffer. It can only be used once and
     * should be destroyed after a 'created' or 'failed' event has been
     * received.
     */
    public fun createParams(): ZwpLinuxBufferParamsV1 {
        val created = connection.createProxy(ZwpLinuxBufferParamsV1, this.version)
        request(1).newId(created).end()
        return created
    }

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
    public fun getDefaultFeedback(): ZwpLinuxDmabufFeedbackV1 {
        check(version >= 4) { "zwp_linux_dmabuf_v1.get_default_feedback needs version 4, this object has $version" }
        val created = connection.createProxy(ZwpLinuxDmabufFeedbackV1, this.version)
        request(2).newId(created).end()
        return created
    }

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
    public fun getSurfaceFeedback(surface: WlSurface): ZwpLinuxDmabufFeedbackV1 {
        check(version >= 4) { "zwp_linux_dmabuf_v1.get_surface_feedback needs version 4, this object has $version" }
        val created = connection.createProxy(ZwpLinuxDmabufFeedbackV1, this.version)
        request(3).newId(created).obj(surface).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val format = args.uint()
                args.end()
                handler?.format(this, format)
            }
            1 -> {
                val format = args.uint()
                val modifierHi = args.uint()
                val modifierLo = args.uint()
                args.end()
                handler?.modifier(this, format, modifierHi, modifierLo)
            }
        }
    }

    public companion object : ProxyType<ZwpLinuxDmabufV1>("zwp_linux_dmabuf_v1", 6) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpLinuxDmabufV1 = ZwpLinuxDmabufV1(connection, id, version)
    }
}
