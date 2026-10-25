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
 * Receive relative pointer motion events
 *
 * The wp_locked_pointer interface represents a locked pointer state.
 *
 * While the lock of this object is active, the wl_pointer objects of the
 * associated seat will not emit any wl_pointer.motion events.
 *
 * This object will send the event 'locked' when the lock is activated.
 * Whenever the lock is activated, it is guaranteed that the locked surface
 * will already have received pointer focus and that the pointer will be
 * within the region passed to the request creating this object.
 *
 * To unlock the pointer, send the destroy request. This will also destroy
 * the wp_locked_pointer object.
 *
 * If the compositor decides to unlock the pointer the unlocked event is
 * sent. See wp_locked_pointer.unlock for details.
 *
 * When unlocking, the compositor may warp the cursor position to the set
 * cursor position hint. If it does, it will not result in any relative
 * motion events emitted via wp_relative_pointer.
 *
 * If the surface the lock was requested on is destroyed and the lock is not
 * yet activated, the wp_locked_pointer object is now defunct and must be
 * destroyed.
 */
public class ZwpLockedPointerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpLockedPointerV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Lock activation event
         *
         * Notification that the pointer lock of the seat's pointer is activated.
         */
        public fun locked(proxy: ZwpLockedPointerV1) {}

        /**
         * Lock deactivation event
         *
         * Notification that the pointer lock of the seat's pointer is no longer
         * active. If this is a oneshot pointer lock (see
         * wp_pointer_constraints.lifetime) this object is now defunct and should
         * be destroyed. If this is a persistent pointer lock (see
         * wp_pointer_constraints.lifetime) this pointer lock may again
         * reactivate in the future.
         */
        public fun unlocked(proxy: ZwpLockedPointerV1) {}
    }

    /**
     * Destroy the locked pointer object
     *
     * Destroy the locked pointer object. If applicable, the compositor will
     * unlock the pointer.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Set the pointer cursor position hint
     *
     * Set the cursor position hint relative to the top left corner of the
     * surface.
     *
     * If the client is drawing its own cursor, it should update the position
     * hint to the position of its own cursor. A compositor may use this
     * information to warp the pointer upon unlock in order to avoid pointer
     * jumps.
     *
     * The cursor position hint is double-buffered state, see
     * wl_surface.commit.
     */
    public fun setCursorPositionHint(surfaceX: Double, surfaceY: Double): Unit {
        if (Wire.tracing) Wire.sent(this, "set_cursor_position_hint", "surface_x=${Wire.show(surfaceX)}, surface_y=${Wire.show(surfaceY)}")
        request(1).fixed(surfaceX).fixed(surfaceY).end()
    }

    /**
     * Set a new lock region
     *
     * Set a new region used to lock the pointer.
     *
     * The new lock region is double-buffered, see wl_surface.commit.
     *
     * For details about the lock region, see wp_locked_pointer.
     */
    public fun setRegion(region: WlRegion?): Unit {
        if (Wire.tracing) Wire.sent(this, "set_region", "region=${Wire.show(region)}")
        request(2).obj(region).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "locked", "")
                handler?.locked(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "unlocked", "")
                handler?.unlocked(this)
            }
        }
    }

    public companion object : ProxyType<ZwpLockedPointerV1>("zwp_locked_pointer_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpLockedPointerV1 = ZwpLockedPointerV1(connection, id, version)
    }
}
