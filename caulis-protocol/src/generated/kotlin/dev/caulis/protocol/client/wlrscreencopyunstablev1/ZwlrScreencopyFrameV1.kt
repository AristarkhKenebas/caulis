// Generated from wlr-screencopy-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrscreencopyunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlBuffer

/**
 * A frame ready for copy
 *
 * This object represents a single frame.
 *
 * When created, a series of buffer events will be sent, each representing a
 * supported buffer type. The "buffer_done" event is sent afterwards to
 * indicate that all supported buffer types have been enumerated. The client
 * will then be able to send a "copy" request. If the capture is successful,
 * the compositor will send a "flags" event followed by a "ready" event.
 *
 * For objects version 2 or lower, wl_shm buffers are always supported, ie.
 * the "buffer" event is guaranteed to be sent.
 *
 * If the capture failed, the "failed" event is sent. This can happen anytime
 * before the "ready" event.
 *
 * Once either a "ready" or a "failed" event is received, the client should
 * destroy the frame.
 */
public class ZwlrScreencopyFrameV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrScreencopyFrameV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Wl_shm buffer information
         *
         * Provides information about wl_shm buffer parameters that need to be
         * used for this frame. This event is sent once after the frame is created
         * if wl_shm buffers are supported.
         */
        public fun buffer(proxy: ZwlrScreencopyFrameV1, format: Int, width: Int, height: Int, stride: Int) {}

        /**
         * Frame flags
         *
         * Provides flags about the frame. This event is sent once before the
         * "ready" event.
         */
        public fun flags(proxy: ZwlrScreencopyFrameV1, flags: Int) {}

        /**
         * Indicates frame is available for reading
         *
         * Called as soon as the frame is copied, indicating it is available
         * for reading. This event includes the time at which the presentation took place.
         *
         * The timestamp is expressed as tv_sec_hi, tv_sec_lo, tv_nsec triples,
         * each component being an unsigned 32-bit value. Whole seconds are in
         * tv_sec which is a 64-bit value combined from tv_sec_hi and tv_sec_lo,
         * and the additional fractional part in tv_nsec as nanoseconds. Hence,
         * for valid timestamps tv_nsec must be in [0, 999999999]. The seconds part
         * may have an arbitrary offset at start.
         *
         * After receiving this event, the client should destroy the object.
         */
        public fun ready(proxy: ZwlrScreencopyFrameV1, tvSecHi: Int, tvSecLo: Int, tvNsec: Int) {}

        /**
         * Frame copy failed
         *
         * This event indicates that the attempted frame copy has failed.
         *
         * After receiving this event, the client should destroy the object.
         */
        public fun failed(proxy: ZwlrScreencopyFrameV1) {}

        /**
         * Carries the coordinates of the damaged region
         *
         * This event is sent right before the ready event when copy_with_damage is
         * requested. It may be generated multiple times for each copy_with_damage
         * request.
         *
         * The arguments describe a box around an area that has changed since the
         * last copy request that was derived from the current screencopy manager
         * instance.
         *
         * The union of all regions received between the call to copy_with_damage
         * and a ready event is the total damage since the prior ready event.
         *
         * Since version 2.
         */
        public fun damage(proxy: ZwlrScreencopyFrameV1, x: Int, y: Int, width: Int, height: Int) {}

        /**
         * Linux-dmabuf buffer information
         *
         * Provides information about linux-dmabuf buffer parameters that need to
         * be used for this frame. This event is sent once after the frame is
         * created if linux-dmabuf buffers are supported.
         *
         * Since version 3.
         */
        public fun linuxDmabuf(proxy: ZwlrScreencopyFrameV1, format: Int, width: Int, height: Int) {}

        /**
         * All buffer types reported
         *
         * This event is sent once after all buffer events have been sent.
         *
         * The client should proceed to create a buffer of one of the supported
         * types, and send a "copy" request.
         *
         * Since version 3.
         */
        public fun bufferDone(proxy: ZwlrScreencopyFrameV1) {}
    }

    /**
     * Copy the frame
     *
     * Copy the frame to the supplied buffer. The buffer must have the
     * correct size, see zwlr_screencopy_frame_v1.buffer and
     * zwlr_screencopy_frame_v1.linux_dmabuf. The buffer needs to have a
     * supported format.
     *
     * If the frame is successfully copied, "flags" and "ready" events are
     * sent. Otherwise, a "failed" event is sent.
     */
    public fun copy(buffer: WlBuffer): Unit {
        if (Wire.tracing) Wire.sent(this, "copy", "buffer=${Wire.show(buffer)}")
        request(0).obj(buffer).end()
    }

    /**
     * Delete this object, used or not
     *
     * Destroys the frame. This request can be sent at any time by the client.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    /**
     * Copy the frame when it's damaged
     *
     * Same as copy, except it waits until there is damage to copy.
     *
     * Since version 2.
     */
    public fun copyWithDamage(buffer: WlBuffer): Unit {
        check(version >= 2) { "zwlr_screencopy_frame_v1.copy_with_damage needs version 2, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "copy_with_damage", "buffer=${Wire.show(buffer)}")
        request(2).obj(buffer).end()
    }

    public object Error {
        /** the object has already been used to copy a wl_buffer */
        public const val ALREADY_USED: Int = 0
        /** buffer attributes are invalid */
        public const val INVALID_BUFFER: Int = 1
    }

    public object Flags {
        /** contents are y-inverted */
        public const val Y_INVERT: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val format = args.uint()
                val width = args.uint()
                val height = args.uint()
                val stride = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "buffer", "format=${Wire.show(format)}, width=${Wire.show(width)}, height=${Wire.show(height)}, stride=${Wire.show(stride)}")
                handler?.buffer(this, format, width, height, stride)
            }
            1 -> {
                val flags = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "flags", "flags=${Wire.show(flags)}")
                handler?.flags(this, flags)
            }
            2 -> {
                val tvSecHi = args.uint()
                val tvSecLo = args.uint()
                val tvNsec = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "ready", "tv_sec_hi=${Wire.show(tvSecHi)}, tv_sec_lo=${Wire.show(tvSecLo)}, tv_nsec=${Wire.show(tvNsec)}")
                handler?.ready(this, tvSecHi, tvSecLo, tvNsec)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "failed", "")
                handler?.failed(this)
            }
            4 -> {
                val x = args.uint()
                val y = args.uint()
                val width = args.uint()
                val height = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "damage", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.damage(this, x, y, width, height)
            }
            5 -> {
                val format = args.uint()
                val width = args.uint()
                val height = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "linux_dmabuf", "format=${Wire.show(format)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.linuxDmabuf(this, format, width, height)
            }
            6 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "buffer_done", "")
                handler?.bufferDone(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrScreencopyFrameV1>("zwlr_screencopy_frame_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrScreencopyFrameV1 = ZwlrScreencopyFrameV1(connection, id, version)
    }
}
