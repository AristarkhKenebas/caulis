// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Sub-surface interface to a wl_surface
 *
 * An additional interface to a wl_surface object, which has been
 * made a sub-surface. A sub-surface has one parent surface. A
 * sub-surface's size and position are not limited to that of the parent.
 * Particularly, a sub-surface is not automatically clipped to its
 * parent's area.
 *
 * A sub-surface becomes mapped, when a non-NULL wl_buffer is applied
 * and the parent surface is mapped. The order of which one happens
 * first is irrelevant. A sub-surface is hidden if the parent becomes
 * hidden, or if a NULL wl_buffer is applied. These rules apply
 * recursively through the tree of surfaces.
 *
 * A sub-surface can be in one of two modes. The possible modes are
 * synchronized and desynchronized, see methods wl_subsurface.set_sync and
 * wl_subsurface.set_desync.
 *
 * The main surface can be thought to be always in desynchronized mode,
 * since it does not have a parent in the sub-surfaces sense.
 *
 * Even if a sub-surface is in desynchronized mode, it will behave as
 * in synchronized mode, if its parent surface behaves as in
 * synchronized mode. This rule is applied recursively throughout the
 * tree of surfaces. This means, that one can set a sub-surface into
 * synchronized mode, and then assume that all its child and grand-child
 * sub-surfaces are synchronized, too, without explicitly setting them.
 *
 * If a surface behaves as in synchronized mode, it is effectively
 * synchronized, otherwise it is effectively desynchronized.
 *
 * A sub-surface is initially in the synchronized mode.
 *
 * The wl_subsurface interface has requests which modify double-buffered
 * state of the parent surface (wl_subsurface.set_position, .place_above and
 * .place_below).
 *
 * Destroying a sub-surface takes effect immediately. If you need to
 * synchronize the removal of a sub-surface to the parent surface update,
 * unmap the sub-surface first by attaching a NULL wl_buffer, update parent,
 * and then destroy the sub-surface.
 *
 * If the parent wl_surface object is destroyed, the sub-surface is
 * unmapped.
 *
 * A sub-surface never has the keyboard focus of any seat.
 *
 * The wl_surface.offset request is ignored: clients must use set_position
 * instead to move the sub-surface.
 */
public class WlSubsurface private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlSubsurface> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_subsurface. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Remove sub-surface interface
         *
         * The sub-surface interface is removed from the wl_surface object
         * that was turned into a sub-surface with a
         * wl_subcompositor.get_subsurface request. The wl_surface's association
         * to the parent is deleted. The wl_surface is unmapped immediately.
         */
        public fun destroy(resource: WlSubsurface)

        /**
         * Reposition the sub-surface
         *
         * This sets the position of the sub-surface, relative to the parent
         * surface.
         *
         * The sub-surface will be moved so that its origin (top left
         * corner pixel) will be at the location x, y of the parent surface
         * coordinate system. The coordinates are not restricted to the parent
         * surface area. Negative values are allowed.
         *
         * The initial position is 0, 0.
         *
         * Position is double-buffered state on the parent surface, see
         * wl_subsurface and wl_surface.commit for more information.
         */
        public fun setPosition(resource: WlSubsurface, x: Int, y: Int)

        /**
         * Restack the sub-surface
         *
         * This sub-surface is taken from the stack, and put back just
         * above the reference surface, changing the z-order of the sub-surfaces.
         * The reference surface must be one of the sibling surfaces, or the
         * parent surface. Using any other surface, including this sub-surface,
         * will cause a protocol error.
         *
         * A new sub-surface is initially added as the top-most in the stack
         * of its siblings and parent.
         *
         * Z-order is double-buffered state on the parent surface, see
         * wl_subsurface and wl_surface.commit for more information.
         */
        public fun placeAbove(resource: WlSubsurface, sibling: WlSurface)

        /**
         * Restack the sub-surface
         *
         * The sub-surface is placed just below the reference surface.
         *
         * See wl_subsurface.place_above.
         */
        public fun placeBelow(resource: WlSubsurface, sibling: WlSurface)

        /**
         * Set sub-surface to synchronized mode
         *
         * Change the commit behaviour of the sub-surface to synchronized
         * mode.
         *
         * See wl_subsurface and wl_surface.commit for more information.
         */
        public fun setSync(resource: WlSubsurface)

        /**
         * Set sub-surface to desynchronized mode
         *
         * Change the commit behaviour of the sub-surface to desynchronized
         * mode.
         *
         * See wl_subsurface and wl_surface.commit for more information.
         */
        public fun setDesync(resource: WlSubsurface)
    }

    public object Error {
        /** wl_surface is not a sibling or the parent */
        public const val BAD_SURFACE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_subsurface is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val x = args.int()
                val y = args.int()
                args.end()
                handler.setPosition(this, x, y)
            }
            2 -> {
                val sibling = args.obj(WlSurface::class.java)
                args.end()
                handler.placeAbove(this, sibling)
            }
            3 -> {
                val sibling = args.obj(WlSurface::class.java)
                args.end()
                handler.placeBelow(this, sibling)
            }
            4 -> {
                args.end()
                handler.setSync(this)
            }
            5 -> {
                args.end()
                handler.setDesync(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_subsurface request $opcode")
        }
    }

    public companion object : ResourceType<WlSubsurface>("wl_subsurface", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlSubsurface = WlSubsurface(client, id, version)
    }
}
