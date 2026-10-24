// Generated from xwayland-shell-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xwaylandshellv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * Context object for Xwayland shell
 *
 * xwayland_shell_v1 is a singleton global object that
 * provides the ability to create a xwayland_surface_v1 object
 * for a given wl_surface.
 *
 * This interface is intended to be bound by the Xwayland server.
 *
 * A compositor must not allow clients other than Xwayland to
 * bind to this interface. A compositor should hide this global
 * from other clients' wl_registry.
 * A client the compositor does not consider to be an Xwayland
 * server attempting to bind this interface will result in
 * an implementation-defined error.
 *
 * An Xwayland server that has bound this interface must not
 * set the `WL_SURFACE_ID` atom on a window.
 */
public class XwaylandShellV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XwaylandShellV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xwayland_shell_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the Xwayland shell object
         *
         * Destroy the xwayland_shell_v1 object.
         *
         * The child objects created via this interface are unaffected.
         */
        public fun destroy(resource: XwaylandShellV1)

        /**
         * Assign the xwayland_surface surface role
         *
         * Create an xwayland_surface_v1 interface for a given wl_surface
         * object and gives it the xwayland_surface role.
         *
         * It is illegal to create an xwayland_surface_v1 for a wl_surface
         * which already has an assigned role and this will result in the
         * `role` protocol error.
         *
         * See the documentation of xwayland_surface_v1 for more details
         * about what an xwayland_surface_v1 is and how it is used.
         */
        public fun getXwaylandSurface(resource: XwaylandShellV1, id: XwaylandSurfaceV1, surface: WlSurface)
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xwayland_shell_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(XwaylandSurfaceV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_xwayland_surface", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.getXwaylandSurface(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xwayland_shell_v1 request $opcode")
        }
    }

    public companion object : ResourceType<XwaylandShellV1>("xwayland_shell_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): XwaylandShellV1 = XwaylandShellV1(client, id, version)
    }
}
