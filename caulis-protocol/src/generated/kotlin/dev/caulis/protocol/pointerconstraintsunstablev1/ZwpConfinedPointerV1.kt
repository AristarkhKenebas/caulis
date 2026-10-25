// Generated from pointer-constraints-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.pointerconstraintsunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlRegion

/**
 * Confined pointer object
 *
 * The wp_confined_pointer interface represents a confined pointer state.
 *
 * This object will send the event 'confined' when the confinement is
 * activated. Whenever the confinement is activated, it is guaranteed that
 * the surface the pointer is confined to will already have received pointer
 * focus and that the pointer will be within the region passed to the request
 * creating this object. It is up to the compositor to decide whether this
 * requires some user interaction and if the pointer will warp to within the
 * passed region if outside.
 *
 * To unconfine the pointer, send the destroy request. This will also destroy
 * the wp_confined_pointer object.
 *
 * If the compositor decides to unconfine the pointer the unconfined event is
 * sent. The wp_confined_pointer object is at this point defunct and should
 * be destroyed.
 */
public class ZwpConfinedPointerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpConfinedPointerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_confined_pointer_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the confined pointer object
         *
         * Destroy the confined pointer object. If applicable, the compositor will
         * unconfine the pointer.
         */
        public fun destroy(resource: ZwpConfinedPointerV1)

        /**
         * Set a new confine region
         *
         * Set a new region used to confine the pointer.
         *
         * The new confine region is double-buffered, see wl_surface.commit.
         *
         * If the confinement is active when the new confinement region is applied
         * and the pointer ends up outside of newly applied region, the pointer may
         * warped to a position within the new confinement region. If warped, a
         * wl_pointer.motion event will be emitted, but no
         * wp_relative_pointer.relative_motion event.
         *
         * The compositor may also, instead of using the new region, unconfine the
         * pointer.
         *
         * For details about the confine region, see wp_confined_pointer.
         */
        public fun setRegion(resource: ZwpConfinedPointerV1, region: WlRegion?)
    }

    /**
     * Pointer confined
     *
     * Notification that the pointer confinement of the seat's pointer is
     * activated.
     */
    public fun sendConfined() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "confined", "")
        event(0).end()
    }

    /**
     * Pointer unconfined
     *
     * Notification that the pointer confinement of the seat's pointer is no
     * longer active. If this is a oneshot pointer confinement (see
     * wp_pointer_constraints.lifetime) this object is now defunct and should
     * be destroyed. If this is a persistent pointer confinement (see
     * wp_pointer_constraints.lifetime) this pointer confinement may again
     * reactivate in the future.
     */
    public fun sendUnconfined() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "unconfined", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_confined_pointer_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val region = args.objOrNull(WlRegion::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_region", "region=${Wire.show(region)}")
                handler.setRegion(this, region)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_confined_pointer_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpConfinedPointerV1>("zwp_confined_pointer_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpConfinedPointerV1 = ZwpConfinedPointerV1(client, id, version)
    }
}
