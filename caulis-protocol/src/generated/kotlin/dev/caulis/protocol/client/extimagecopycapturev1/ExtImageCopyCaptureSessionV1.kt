// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecopycapturev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ExtImageCopyCaptureSessionV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtImageCopyCaptureSessionV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Image capture source dimensions
         *
         * Provides the dimensions of the source image in buffer pixel coordinates.
         *
         * The client must attach buffers that match this size.
         */
        public fun bufferSize(proxy: ExtImageCopyCaptureSessionV1, width: Int, height: Int) {}

        /**
         * Shm buffer format
         *
         * Provides the format that must be used for shared-memory buffers.
         *
         * This event may be emitted multiple times, in which case the client may
         * choose any given format.
         */
        public fun shmFormat(proxy: ExtImageCopyCaptureSessionV1, format: Int) {}

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
        public fun dmabufDevice(proxy: ExtImageCopyCaptureSessionV1, device: ByteArray) {}

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
        public fun dmabufFormat(proxy: ExtImageCopyCaptureSessionV1, format: Int, modifiers: ByteArray) {}

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
        public fun done(proxy: ExtImageCopyCaptureSessionV1) {}

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
        public fun stopped(proxy: ExtImageCopyCaptureSessionV1) {}
    }

    /**
     * Create a frame
     *
     * Create a capture frame for this session.
     *
     * At most one frame object can exist for a given session at any time. If
     * a client sends a create_frame request before a previous frame object
     * has been destroyed, the duplicate_frame protocol error is raised.
     */
    public fun createFrame(): ExtImageCopyCaptureFrameV1 {
        val created = connection.createProxy(ExtImageCopyCaptureFrameV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_frame", "frame=${Wire.show(created)}")
        request(0).newId(created).end()
        return created
    }

    /**
     * Delete this object
     *
     * Destroys the session. This request can be sent at any time by the
     * client.
     *
     * This request doesn't affect ext_image_copy_capture_frame_v1 objects created by
     * this object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** create_frame sent before destroying previous frame */
        public const val DUPLICATE_FRAME: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val width = args.uint()
                val height = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "buffer_size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.bufferSize(this, width, height)
            }
            1 -> {
                val format = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "shm_format", "format=${Wire.show(format)}")
                handler?.shmFormat(this, format)
            }
            2 -> {
                val device = args.array()
                args.end()
                if (Wire.tracing) Wire.received(this, "dmabuf_device", "device=${Wire.show(device)}")
                handler?.dmabufDevice(this, device)
            }
            3 -> {
                val format = args.uint()
                val modifiers = args.array()
                args.end()
                if (Wire.tracing) Wire.received(this, "dmabuf_format", "format=${Wire.show(format)}, modifiers=${Wire.show(modifiers)}")
                handler?.dmabufFormat(this, format, modifiers)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            5 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "stopped", "")
                handler?.stopped(this)
            }
        }
    }

    public companion object : ProxyType<ExtImageCopyCaptureSessionV1>("ext_image_copy_capture_session_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtImageCopyCaptureSessionV1 = ExtImageCopyCaptureSessionV1(connection, id, version)
    }
}
