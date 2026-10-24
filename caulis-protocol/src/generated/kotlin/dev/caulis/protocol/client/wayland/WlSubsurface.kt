// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class WlSubsurface private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlSubsurface> get() = Companion

    /**
     * Remove sub-surface interface
     *
     * The sub-surface interface is removed from the wl_surface object
     * that was turned into a sub-surface with a
     * wl_subcompositor.get_subsurface request. The wl_surface's association
     * to the parent is deleted. The wl_surface is unmapped immediately.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun setPosition(x: Int, y: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        request(1).int(x).int(y).end()
    }

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
    public fun placeAbove(sibling: WlSurface): Unit {
        if (Wire.tracing) Wire.sent(this, "place_above", "sibling=${Wire.show(sibling)}")
        request(2).obj(sibling).end()
    }

    /**
     * Restack the sub-surface
     *
     * The sub-surface is placed just below the reference surface.
     *
     * See wl_subsurface.place_above.
     */
    public fun placeBelow(sibling: WlSurface): Unit {
        if (Wire.tracing) Wire.sent(this, "place_below", "sibling=${Wire.show(sibling)}")
        request(3).obj(sibling).end()
    }

    /**
     * Set sub-surface to synchronized mode
     *
     * Change the commit behaviour of the sub-surface to synchronized
     * mode.
     *
     * See wl_subsurface and wl_surface.commit for more information.
     */
    public fun setSync(): Unit {
        if (Wire.tracing) Wire.sent(this, "set_sync", "")
        request(4).end()
    }

    /**
     * Set sub-surface to desynchronized mode
     *
     * Change the commit behaviour of the sub-surface to desynchronized
     * mode.
     *
     * See wl_subsurface and wl_surface.commit for more information.
     */
    public fun setDesync(): Unit {
        if (Wire.tracing) Wire.sent(this, "set_desync", "")
        request(5).end()
    }

    public object Error {
        /** wl_surface is not a sibling or the parent */
        public const val BAD_SURFACE: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlSubsurface>("wl_subsurface", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlSubsurface = WlSubsurface(connection, id, version)
    }
}
