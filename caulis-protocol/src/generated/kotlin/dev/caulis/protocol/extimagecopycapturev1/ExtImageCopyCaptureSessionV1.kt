// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecopycapturev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Image copy capture session
 *
 * This object represents an active image copy capture session.
 *
 * After a capture session is created, buffer constraint events will be
 * emitted from the compositor to tell the client which buffer types and
 * formats are supported for reading from the session. The compositor may
 * re-send buffer constraint events whenever they change.
 *
 * To advertise buffer constraints, the compositor must send in no
 * particular order: zero or more shm_format and dmabuf_format events, zero
 * or one dmabuf_device event, and exactly one buffer_size event. Then the
 * compositor must send a done event.
 *
 * When the client has received all the buffer constraints, it can create a
 * buffer accordingly, attach it to the capture session using the
 * attach_buffer request, set the buffer damage using the damage_buffer
 * request and then send the capture request.
 */
public class ExtImageCopyCaptureSessionV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtImageCopyCaptureSessionV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_image_copy_capture_session_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a frame
         *
         * Create a capture frame for this session.
         *
         * At most one frame object can exist for a given session at any time. If
         * a client sends a create_frame request before a previous frame object
         * has been destroyed, the duplicate_frame protocol error is raised.
         */
        public fun createFrame(resource: ExtImageCopyCaptureSessionV1, frame: ExtImageCopyCaptureFrameV1)

        /**
         * Delete this object
         *
         * Destroys the session. This request can be sent at any time by the
         * client.
         *
         * This request doesn't affect ext_image_copy_capture_frame_v1 objects created by
         * this object.
         */
        public fun destroy(resource: ExtImageCopyCaptureSessionV1)
    }

    /**
     * Image capture source dimensions
     *
     * Provides the dimensions of the source image in buffer pixel coordinates.
     *
     * The client must attach buffers that match this size.
     */
    public fun sendBufferSize(width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "buffer_size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(0).uint(width).uint(height).end()
    }

    /**
     * Shm buffer format
     *
     * Provides the format that must be used for shared-memory buffers.
     *
     * This event may be emitted multiple times, in which case the client may
     * choose any given format.
     */
    public fun sendShmFormat(format: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "shm_format", "format=${Wire.show(format)}")
        event(1).uint(format).end()
    }

    /**
     * Dma-buf device
     *
     * This event advertises the device buffers must be allocated on for
     * dma-buf buffers.
     *
     * In general the device is a DRM node. The DRM node type (primary vs.
     * render) is unspecified. Clients must not rely on the compositor sending
     * a particular node type. Clients cannot check two devices for equality
     * by comparing the dev_t value.
     */
    public fun sendDmabufDevice(device: ByteArray) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "dmabuf_device", "device=${Wire.show(device)}")
        event(2).array(device).end()
    }

    /**
     * Dma-buf format
     *
     * Provides the format that must be used for dma-buf buffers.
     *
     * The client may choose any of the modifiers advertised in the array of
     * 64-bit unsigned integers.
     *
     * This event may be emitted multiple times, in which case the client may
     * choose any given format.
     */
    public fun sendDmabufFormat(format: Int, modifiers: ByteArray) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "dmabuf_format", "format=${Wire.show(format)}, modifiers=${Wire.show(modifiers)}")
        event(3).uint(format).array(modifiers).end()
    }

    /**
     * All constraints have been sent
     *
     * This event is sent once when all buffer constraint events have been
     * sent.
     *
     * The compositor must always end a batch of buffer constraint events with
     * this event, regardless of whether it sends the initial constraints or
     * an update.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "")
        event(4).end()
    }

    /**
     * Session is no longer available
     *
     * This event indicates that the capture session has stopped and is no
     * longer available. This can happen in a number of cases, e.g. when the
     * underlying source is destroyed, if the user decides to end the image
     * capture, or if an unrecoverable runtime error has occurred.
     *
     * The client should destroy the session after receiving this event.
     */
    public fun sendStopped() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "stopped", "")
        event(5).end()
    }

    public object Error {
        /** create_frame sent before destroying previous frame */
        public const val DUPLICATE_FRAME: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_image_copy_capture_session_v1 is not implemented")
        when (opcode) {
            0 -> {
                val frameId = args.newId()
                args.end()
                val frame = client.register(ExtImageCopyCaptureFrameV1.create(client, frameId, version))
                if (Wire.tracing) Wire.request(this, "create_frame", "frame=${Wire.show(frame)}")
                handler.createFrame(this, frame)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_image_copy_capture_session_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtImageCopyCaptureSessionV1>("ext_image_copy_capture_session_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtImageCopyCaptureSessionV1 = ExtImageCopyCaptureSessionV1(client, id, version)
    }
}
