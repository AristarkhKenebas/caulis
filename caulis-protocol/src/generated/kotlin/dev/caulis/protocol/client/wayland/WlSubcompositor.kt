// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Sub-surface compositing
 *
 * The global interface exposing sub-surface compositing capabilities.
 * A wl_surface, that has sub-surfaces associated, is called the
 * parent surface. Sub-surfaces can be arbitrarily nested and create
 * a tree of sub-surfaces.
 *
 * The root surface in a tree of sub-surfaces is the main
 * surface. The main surface cannot be a sub-surface, because
 * sub-surfaces must always have a parent.
 *
 * A main surface with its sub-surfaces forms a (compound) window.
 * For window management purposes, this set of wl_surface objects is
 * to be considered as a single window, and it should also behave as
 * such.
 *
 * The aim of sub-surfaces is to offload some of the compositing work
 * within a window from clients to the compositor. A prime example is
 * a video player with decorations and video in separate wl_surface
 * objects. This should allow the compositor to pass YUV video buffer
 * processing to dedicated overlay hardware when possible.
 */
public class WlSubcompositor private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlSubcompositor> get() = Companion

    /**
     * Unbind from the subcompositor interface
     *
     * Informs the server that the client will not be using this
     * protocol object anymore. This does not affect any other
     * objects, wl_subsurface objects included.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Give a surface the role sub-surface
     *
     * Create a sub-surface interface for the given surface, and
     * associate it with the given parent surface. This turns a
     * plain wl_surface into a sub-surface.
     *
     * The to-be sub-surface must not already have another role, and it
     * must not have an existing wl_subsurface object. Otherwise the
     * bad_surface protocol error is raised.
     *
     * Adding sub-surfaces to a parent is a double-buffered operation on the
     * parent (see wl_surface.commit). The effect of adding a sub-surface
     * becomes visible on the next time the state of the parent surface is
     * applied.
     *
     * The parent surface must not be one of the child surface's descendants,
     * and the parent must be different from the child surface, otherwise the
     * bad_parent protocol error is raised.
     *
     * This request modifies the behaviour of wl_surface.commit request on
     * the sub-surface, see the documentation on wl_subsurface interface.
     */
    public fun getSubsurface(surface: WlSurface, parent: WlSurface): WlSubsurface {
        val created = connection.createProxy(WlSubsurface, this.version)
        request(1).newId(created).obj(surface).obj(parent).end()
        return created
    }

    public object Error {
        /** the to-be sub-surface is invalid */
        public const val BAD_SURFACE: Int = 0
        /** the to-be sub-surface parent is invalid */
        public const val BAD_PARENT: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlSubcompositor>("wl_subcompositor", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlSubcompositor = WlSubcompositor(connection, id, version)
    }
}
