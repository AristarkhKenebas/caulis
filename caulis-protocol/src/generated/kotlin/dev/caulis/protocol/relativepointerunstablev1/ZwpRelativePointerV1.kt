// Generated from relative-pointer-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.relativepointerunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Relative pointer object
 *
 * A wp_relative_pointer object is an extension to the wl_pointer interface
 * used for emitting relative pointer events. It shares the same focus as
 * wl_pointer objects of the same seat and will only emit events when it has
 * focus.
 */
public class ZwpRelativePointerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpRelativePointerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_relative_pointer_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Release the relative pointer object
         */
        public fun destroy(resource: ZwpRelativePointerV1)
    }

    /**
     * Relative pointer motion
     *
     * Relative x/y pointer motion from the pointer of the seat associated with
     * this object.
     *
     * A relative motion is in the same dimension as regular wl_pointer motion
     * events, except they do not represent an absolute position. For example,
     * moving a pointer from (x, y) to (x', y') would have the equivalent
     * relative motion (x' - x, y' - y). If a pointer motion caused the
     * absolute pointer position to be clipped by for example the edge of the
     * monitor, the relative motion is unaffected by the clipping and will
     * represent the unclipped motion.
     *
     * This event also contains non-accelerated motion deltas. The
     * non-accelerated delta is, when applicable, the regular pointer motion
     * delta as it was before having applied motion acceleration and other
     * transformations such as normalization.
     *
     * Note that the non-accelerated delta does not represent 'raw' events as
     * they were read from some device. Pointer motion acceleration is device-
     * and configuration-specific and non-accelerated deltas and accelerated
     * deltas may have the same value on some devices.
     *
     * Relative motions are not coupled to wl_pointer.motion events, and can be
     * sent in combination with such events, but also independently. There may
     * also be scenarios where wl_pointer.motion is sent, but there is no
     * relative motion. The order of an absolute and relative motion event
     * originating from the same physical motion is not guaranteed.
     *
     * If the client needs button events or focus state, it can receive them
     * from a wl_pointer object of the same seat that the wp_relative_pointer
     * object is associated with.
     */
    public fun sendRelativeMotion(utimeHi: Int, utimeLo: Int, dx: Double, dy: Double, dxUnaccel: Double, dyUnaccel: Double) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "relative_motion", "utime_hi=${Wire.show(utimeHi)}, utime_lo=${Wire.show(utimeLo)}, dx=${Wire.show(dx)}, dy=${Wire.show(dy)}, dx_unaccel=${Wire.show(dxUnaccel)}, dy_unaccel=${Wire.show(dyUnaccel)}")
        event(0).uint(utimeHi).uint(utimeLo).fixed(dx).fixed(dy).fixed(dxUnaccel).fixed(dyUnaccel).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_relative_pointer_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_relative_pointer_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpRelativePointerV1>("zwp_relative_pointer_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpRelativePointerV1 = ZwpRelativePointerV1(client, id, version)
    }
}
