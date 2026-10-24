// Generated from wlr-screencopy-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrscreencopyunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlDisplay

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
public class ZwlrScreencopyFrameV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrScreencopyFrameV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_screencopy_frame_v1. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun copy(resource: ZwlrScreencopyFrameV1, buffer: WlBuffer)

        /**
         * Delete this object, used or not
         *
         * Destroys the frame. This request can be sent at any time by the client.
         */
        public fun destroy(resource: ZwlrScreencopyFrameV1)

        /**
         * Copy the frame when it's damaged
         *
         * Same as copy, except it waits until there is damage to copy.
         *
         * Since version 2.
         */
        public fun copyWithDamage(resource: ZwlrScreencopyFrameV1, buffer: WlBuffer)
    }

    /**
     * Wl_shm buffer information
     *
     * Provides information about wl_shm buffer parameters that need to be
     * used for this frame. This event is sent once after the frame is created
     * if wl_shm buffers are supported.
     */
    public fun sendBuffer(format: Int, width: Int, height: Int, stride: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "buffer", "format=${Wire.show(format)}, width=${Wire.show(width)}, height=${Wire.show(height)}, stride=${Wire.show(stride)}")
        event(0).uint(format).uint(width).uint(height).uint(stride).end()
    }

    /**
     * Frame flags
     *
     * Provides flags about the frame. This event is sent once before the
     * "ready" event.
     */
    public fun sendFlags(flags: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "flags", "flags=${Wire.show(flags)}")
        event(1).uint(flags).end()
    }

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
    public fun sendReady(tvSecHi: Int, tvSecLo: Int, tvNsec: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "ready", "tv_sec_hi=${Wire.show(tvSecHi)}, tv_sec_lo=${Wire.show(tvSecLo)}, tv_nsec=${Wire.show(tvNsec)}")
        event(2).uint(tvSecHi).uint(tvSecLo).uint(tvNsec).end()
    }

    /**
     * Frame copy failed
     *
     * This event indicates that the attempted frame copy has failed.
     *
     * After receiving this event, the client should destroy the object.
     */
    public fun sendFailed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "")
        event(3).end()
    }

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
    public fun sendDamage(x: Int, y: Int, width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "damage", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(4).uint(x).uint(y).uint(width).uint(height).end()
    }

    /**
     * Linux-dmabuf buffer information
     *
     * Provides information about linux-dmabuf buffer parameters that need to
     * be used for this frame. This event is sent once after the frame is
     * created if linux-dmabuf buffers are supported.
     *
     * Since version 3.
     */
    public fun sendLinuxDmabuf(format: Int, width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "linux_dmabuf", "format=${Wire.show(format)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(5).uint(format).uint(width).uint(height).end()
    }

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
    public fun sendBufferDone() {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "buffer_done", "")
        event(6).end()
    }

    public object Error {
        /** the object has already been used to copy a wl_buffer */
        public const val ALREADY_USED: Int = 0
        /** buffer attributes are invalid */
        public const val INVALID_BUFFER: Int = 1
    }

    /**
     * Bit flags: combine with `or`.
     */
    public object Flags {
        /** contents are y-inverted */
        public const val Y_INVERT: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_screencopy_frame_v1 is not implemented")
        when (opcode) {
            0 -> {
                val buffer = args.obj(WlBuffer::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "copy", "buffer=${Wire.show(buffer)}")
                handler.copy(this, buffer)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_screencopy_frame_v1.copy_with_damage needs version 2")
                val buffer = args.obj(WlBuffer::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "copy_with_damage", "buffer=${Wire.show(buffer)}")
                handler.copyWithDamage(this, buffer)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_screencopy_frame_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrScreencopyFrameV1>("zwlr_screencopy_frame_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrScreencopyFrameV1 = ZwlrScreencopyFrameV1(client, id, version)
    }
}
