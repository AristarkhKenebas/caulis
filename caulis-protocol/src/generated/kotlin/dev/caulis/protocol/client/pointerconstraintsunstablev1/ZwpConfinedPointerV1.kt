// Generated from pointer-constraints-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.pointerconstraintsunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlRegion

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
public class ZwpConfinedPointerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpConfinedPointerV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Pointer confined
         *
         * Notification that the pointer confinement of the seat's pointer is
         * activated.
         */
        public fun confined(proxy: ZwpConfinedPointerV1) {}

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
        public fun unconfined(proxy: ZwpConfinedPointerV1) {}
    }

    /**
     * Destroy the confined pointer object
     *
     * Destroy the confined pointer object. If applicable, the compositor will
     * unconfine the pointer.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun setRegion(region: WlRegion?): Unit {
        if (Wire.tracing) Wire.sent(this, "set_region", "region=${Wire.show(region)}")
        request(1).obj(region).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "confined", "")
                handler?.confined(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "unconfined", "")
                handler?.unconfined(this)
            }
        }
    }

    public companion object : ProxyType<ZwpConfinedPointerV1>("zwp_confined_pointer_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpConfinedPointerV1 = ZwpConfinedPointerV1(connection, id, version)
    }
}
