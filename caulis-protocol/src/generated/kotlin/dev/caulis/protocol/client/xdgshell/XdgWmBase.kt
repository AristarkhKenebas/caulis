// Generated from xdg-shell.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgshell

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Create desktop-style surfaces
 *
 * The xdg_wm_base interface is exposed as a global object enabling clients
 * to turn their wl_surfaces into windows in a desktop environment. It
 * defines the basic functionality needed for clients and the compositor to
 * create windows that can be dragged, resized, maximized, etc, as well as
 * creating transient windows such as popup menus.
 */
public class XdgWmBase private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgWmBase> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Check if the client is alive
         *
         * The ping event asks the client if it's still alive. Pass the
         * serial specified in the event back to the compositor by sending
         * a "pong" request back with the specified serial. See xdg_wm_base.pong.
         *
         * Compositors can use this to determine if the client is still
         * alive. It's unspecified what will happen if the client doesn't
         * respond to the ping request, or in what timeframe. Clients should
         * try to respond in a reasonable amount of time. The “unresponsive”
         * error is provided for compositors that wish to disconnect unresponsive
         * clients.
         *
         * A compositor is free to ping in any way it wants, but a client must
         * always respond to any xdg_wm_base object it created.
         */
        public fun ping(proxy: XdgWmBase, serial: Int) {}
    }

    /**
     * Destroy xdg_wm_base
     *
     * Destroy this xdg_wm_base object.
     *
     * Destroying a bound xdg_wm_base object while there are surfaces
     * still alive created by this xdg_wm_base object instance is illegal
     * and will result in a defunct_surfaces error.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a positioner object
     *
     * Create a positioner object. A positioner object is used to position
     * surfaces relative to some parent surface. See the interface description
     * and xdg_surface.get_popup for details.
     */
    public fun createPositioner(): XdgPositioner {
        val created = connection.createProxy(XdgPositioner, this.version)
        request(1).newId(created).end()
        return created
    }

    /**
     * Create a shell surface from a surface
     *
     * This creates an xdg_surface for the given surface. An xdg_surface is
     * used as basis to define a role to a given surface, such as xdg_toplevel
     * or xdg_popup. It also manages functionality shared between xdg_surface
     * based surface roles.
     *
     * While xdg_surface itself is not a role, the corresponding surface may
     * only be assigned a role extending xdg_surface, such as xdg_toplevel or
     * xdg_popup. It is illegal to create an xdg_surface for a wl_surface which
     * already has anassigned role and this will result in a role error.
     *
     * See the documentation of xdg_surface for more details about what an
     * xdg_surface is and how it is used.
     */
    public fun getXdgSurface(surface: WlSurface): XdgSurface {
        val created = connection.createProxy(XdgSurface, this.version)
        request(2).newId(created).obj(surface).end()
        return created
    }

    /**
     * Respond to a ping event
     *
     * A client must respond to a ping event with a pong request or
     * the client may be deemed unresponsive. See xdg_wm_base.ping
     * and xdg_wm_base.error.unresponsive.
     */
    public fun pong(serial: Int): Unit {
        request(3).uint(serial).end()
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
        /** xdg_wm_base was destroyed before children */
        public const val DEFUNCT_SURFACES: Int = 1
        /** the client tried to map or destroy a non-topmost popup */
        public const val NOT_THE_TOPMOST_POPUP: Int = 2
        /** the client specified an invalid popup parent surface */
        public const val INVALID_POPUP_PARENT: Int = 3
        /** the client provided an invalid surface state */
        public const val INVALID_SURFACE_STATE: Int = 4
        /** the client provided an invalid positioner */
        public const val INVALID_POSITIONER: Int = 5
        /** the client didn’t respond to a ping event in time */
        public const val UNRESPONSIVE: Int = 6
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val serial = args.uint()
                args.end()
                handler?.ping(this, serial)
            }
        }
    }

    public companion object : ProxyType<XdgWmBase>("xdg_wm_base", 7) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgWmBase = XdgWmBase(connection, id, version)
    }
}
