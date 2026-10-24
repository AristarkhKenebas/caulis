// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecopycapturev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlBuffer

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
public class ExtImageCopyCaptureFrameV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtImageCopyCaptureFrameV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Buffer transform
         *
         * This event is sent before the ready event and holds the transform that
         * the compositor has applied to the buffer contents.
         */
        public fun transform(proxy: ExtImageCopyCaptureFrameV1, transform: Int) {}

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
        public fun damage(proxy: ExtImageCopyCaptureFrameV1, x: Int, y: Int, width: Int, height: Int) {}

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
        public fun presentationTime(proxy: ExtImageCopyCaptureFrameV1, tvSecHi: Int, tvSecLo: Int, tvNsec: Int) {}

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
        public fun ready(proxy: ExtImageCopyCaptureFrameV1) {}

        /**
         * Capture failed
         *
         * This event indicates that the attempted frame copy has failed.
         *
         * After receiving this event, the client must destroy the object.
         */
        public fun failed(proxy: ExtImageCopyCaptureFrameV1, reason: Int) {}
    }

    /**
     * Destroy this object
     *
     * Destroys the frame. This request can be sent at any time by the
     * client.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun attachBuffer(buffer: WlBuffer): Unit {
        if (Wire.tracing) Wire.sent(this, "attach_buffer", "buffer=${Wire.show(buffer)}")
        request(1).obj(buffer).end()
    }

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
    public fun damageBuffer(x: Int, y: Int, width: Int, height: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "damage_buffer", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        request(2).int(x).int(y).int(width).int(height).end()
    }

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
    public fun capture(): Unit {
        if (Wire.tracing) Wire.sent(this, "capture", "")
        request(3).end()
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val transform = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "transform", "transform=${Wire.show(transform)}")
                handler?.transform(this, transform)
            }
            1 -> {
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "damage", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.damage(this, x, y, width, height)
            }
            2 -> {
                val tvSecHi = args.uint()
                val tvSecLo = args.uint()
                val tvNsec = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "presentation_time", "tv_sec_hi=${Wire.show(tvSecHi)}, tv_sec_lo=${Wire.show(tvSecLo)}, tv_nsec=${Wire.show(tvNsec)}")
                handler?.presentationTime(this, tvSecHi, tvSecLo, tvNsec)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "ready", "")
                handler?.ready(this)
            }
            4 -> {
                val reason = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "failed", "reason=${Wire.show(reason)}")
                handler?.failed(this, reason)
            }
        }
    }

    public companion object : ProxyType<ExtImageCopyCaptureFrameV1>("ext_image_copy_capture_frame_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtImageCopyCaptureFrameV1 = ExtImageCopyCaptureFrameV1(connection, id, version)
    }
}
