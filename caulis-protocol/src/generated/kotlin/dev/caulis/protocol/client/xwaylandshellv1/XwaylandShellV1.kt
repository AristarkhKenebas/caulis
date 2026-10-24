// Generated from xwayland-shell-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xwaylandshellv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class XwaylandShellV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XwaylandShellV1> get() = Companion

    /**
     * Destroy the Xwayland shell object
     *
     * Destroy the xwayland_shell_v1 object.
     *
     * The child objects created via this interface are unaffected.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun getXwaylandSurface(surface: WlSurface): XwaylandSurfaceV1 {
        val created = connection.createProxy(XwaylandSurfaceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_xwayland_surface", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(1).newId(created).obj(surface).end()
        return created
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XwaylandShellV1>("xwayland_shell_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XwaylandShellV1 = XwaylandShellV1(connection, id, version)
    }
}
