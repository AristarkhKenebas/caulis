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
public class ZwpLockedPointerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpLockedPointerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_locked_pointer_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the locked pointer object
         *
         * Destroy the locked pointer object. If applicable, the compositor will
         * unlock the pointer.
         */
        public fun destroy(resource: ZwpLockedPointerV1)

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
        public fun setCursorPositionHint(resource: ZwpLockedPointerV1, surfaceX: Double, surfaceY: Double)

        /**
         * Set a new lock region
         *
         * Set a new region used to lock the pointer.
         *
         * The new lock region is double-buffered, see wl_surface.commit.
         *
         * For details about the lock region, see wp_locked_pointer.
         */
        public fun setRegion(resource: ZwpLockedPointerV1, region: WlRegion?)
    }

    /**
     * Lock activation event
     *
     * Notification that the pointer lock of the seat's pointer is activated.
     */
    public fun sendLocked() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "locked", "")
        event(0).end()
    }

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
    public fun sendUnlocked() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "unlocked", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_locked_pointer_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val surfaceX = args.fixed()
                val surfaceY = args.fixed()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_cursor_position_hint", "surface_x=${Wire.show(surfaceX)}, surface_y=${Wire.show(surfaceY)}")
                handler.setCursorPositionHint(this, surfaceX, surfaceY)
            }
            2 -> {
                val region = args.objOrNull(WlRegion::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_region", "region=${Wire.show(region)}")
                handler.setRegion(this, region)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_locked_pointer_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpLockedPointerV1>("zwp_locked_pointer_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpLockedPointerV1 = ZwpLockedPointerV1(client, id, version)
    }
}
