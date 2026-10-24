// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

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
public class WlSubcompositor private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlSubcompositor> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_subcompositor. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Unbind from the subcompositor interface
         *
         * Informs the server that the client will not be using this
         * protocol object anymore. This does not affect any other
         * objects, wl_subsurface objects included.
         */
        public fun destroy(resource: WlSubcompositor)

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
        public fun getSubsurface(resource: WlSubcompositor, id: WlSubsurface, surface: WlSurface, parent: WlSurface)
    }

    public object Error {
        /** the to-be sub-surface is invalid */
        public const val BAD_SURFACE: Int = 0
        /** the to-be sub-surface parent is invalid */
        public const val BAD_PARENT: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_subcompositor is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                val parent = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(WlSubsurface.create(client, idId, version))
                handler.getSubsurface(this, id, surface, parent)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_subcompositor request $opcode")
        }
    }

    public companion object : ResourceType<WlSubcompositor>("wl_subcompositor", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlSubcompositor = WlSubcompositor(client, id, version)
    }
}
