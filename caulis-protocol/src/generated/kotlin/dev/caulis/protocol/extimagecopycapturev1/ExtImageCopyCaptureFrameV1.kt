// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecopycapturev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Image capture frame
 *
 * This object represents an image capture frame.
 *
 * The client should attach a buffer, damage the buffer, and then send a
 * capture request.
 *
 * If the capture is successful, the compositor must send the frame metadata
 * (transform, damage, presentation_time in any order) followed by the ready
 * event.
 *
 * If the capture fails, the compositor must send the failed event.
 */
public class ExtImageCopyCaptureFrameV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtImageCopyCaptureFrameV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_image_copy_capture_frame_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy this object
         *
         * Destroys the frame. This request can be sent at any time by the
         * client.
         */
        public fun destroy(resource: ExtImageCopyCaptureFrameV1)

        /**
         * Attach buffer to session
         *
         * Attach a buffer to the session.
         *
         * The wl_buffer.release request is unused.
         *
         * The new buffer replaces any previously attached buffer.
         *
         * This request must not be sent after capture, or else the
         * already_captured protocol error is raised.
         */
        public fun attachBuffer(resource: ExtImageCopyCaptureFrameV1, buffer: WlBuffer)

        /**
         * Damage buffer
         *
         * Apply damage to the buffer which is to be captured next. This request
         * may be sent multiple times to describe a region.
         *
         * The client indicates the accumulated damage since this wl_buffer was
         * last captured. During capture, the compositor will update the buffer
         * with at least the union of the region passed by the client and the
         * region advertised by ext_image_copy_capture_frame_v1.damage.
         *
         * When a wl_buffer is captured for the first time, or when the client
         * doesn't track damage, the client must damage the whole buffer.
         *
         * This is for optimisation purposes. The compositor may use this
         * information to reduce copying.
         *
         * These coordinates originate from the upper left corner of the buffer.
         *
         * If x or y are strictly negative, or if width or height are negative or
         * zero, the invalid_buffer_damage protocol error is raised.
         *
         * This request must not be sent after capture, or else the
         * already_captured protocol error is raised.
         */
        public fun damageBuffer(resource: ExtImageCopyCaptureFrameV1, x: Int, y: Int, width: Int, height: Int)

        /**
         * Capture a frame
         *
         * Capture a frame.
         *
         * Unless this is the first successful captured frame performed in this
         * session, the compositor may wait an indefinite amount of time for the
         * source content to change before performing the copy.
         *
         * This request may only be sent once, or else the already_captured
         * protocol error is raised. A buffer must be attached before this request
         * is sent, or else the no_buffer protocol error is raised.
         */
        public fun capture(resource: ExtImageCopyCaptureFrameV1)
    }

    /**
     * Buffer transform
     *
     * This event is sent before the ready event and holds the transform that
     * the compositor has applied to the buffer contents.
     */
    public fun sendTransform(transform: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "transform", "transform=${Wire.show(transform)}")
        event(0).uint(transform).end()
    }

    /**
     * Buffer damaged region
     *
     * This event is sent before the ready event. It may be generated multiple
     * times to describe a region.
     *
     * The first captured frame in a session will always carry full damage.
     * Subsequent frames' damaged regions describe which parts of the buffer
     * have changed since the last ready event.
     *
     * These coordinates originate in the upper left corner of the buffer.
     */
    public fun sendDamage(x: Int, y: Int, width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "damage", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(1).int(x).int(y).int(width).int(height).end()
    }

    /**
     * Presentation time of the frame
     *
     * This event indicates the time at which the frame is presented to the
     * output in system monotonic time. This event is sent before the ready
     * event.
     *
     * The timestamp is expressed as tv_sec_hi, tv_sec_lo, tv_nsec triples,
     * each component being an unsigned 32-bit value. Whole seconds are in
     * tv_sec which is a 64-bit value combined from tv_sec_hi and tv_sec_lo,
     * and the additional fractional part in tv_nsec as nanoseconds. Hence,
     * for valid timestamps tv_nsec must be in [0, 999999999].
     */
    public fun sendPresentationTime(tvSecHi: Int, tvSecLo: Int, tvNsec: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "presentation_time", "tv_sec_hi=${Wire.show(tvSecHi)}, tv_sec_lo=${Wire.show(tvSecLo)}, tv_nsec=${Wire.show(tvNsec)}")
        event(2).uint(tvSecHi).uint(tvSecLo).uint(tvNsec).end()
    }

    /**
     * Frame is available for reading
     *
     * Called as soon as the frame is copied, indicating it is available
     * for reading.
     *
     * The buffer may be re-used by the client after this event.
     *
     * After receiving this event, the client must destroy the object.
     */
    public fun sendReady() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "ready", "")
        event(3).end()
    }

    /**
     * Capture failed
     *
     * This event indicates that the attempted frame copy has failed.
     *
     * After receiving this event, the client must destroy the object.
     */
    public fun sendFailed(reason: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "reason=${Wire.show(reason)}")
        event(4).uint(reason).end()
    }

    public object Error {
        /** capture sent without attach_buffer */
        public const val NO_BUFFER: Int = 1
        /** invalid buffer damage */
        public const val INVALID_BUFFER_DAMAGE: Int = 2
        /** capture request has been sent */
        public const val ALREADY_CAPTURED: Int = 3
    }

    public object FailureReason {
        public const val UNKNOWN: Int = 0
        public const val BUFFER_CONSTRAINTS: Int = 1
        public const val STOPPED: Int = 2
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_image_copy_capture_frame_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val buffer = args.obj(WlBuffer::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "attach_buffer", "buffer=${Wire.show(buffer)}")
                handler.attachBuffer(this, buffer)
            }
            2 -> {
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "damage_buffer", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler.damageBuffer(this, x, y, width, height)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "capture", "")
                handler.capture(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_image_copy_capture_frame_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtImageCopyCaptureFrameV1>("ext_image_copy_capture_frame_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtImageCopyCaptureFrameV1 = ExtImageCopyCaptureFrameV1(client, id, version)
    }
}
