// Generated from xdg-shell.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.xdgshell

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType
import dev.tessera.protocol.wayland.WlDisplay
import dev.tessera.protocol.wayland.WlSurface

/**
 * Create desktop-style surfaces
 *
 * The xdg_wm_base interface is exposed as a global object enabling clients
 * to turn their wl_surfaces into windows in a desktop environment. It
 * defines the basic functionality needed for clients and the compositor to
 * create windows that can be dragged, resized, maximized, etc, as well as
 * creating transient windows such as popup menus.
 */
public class XdgWmBase private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgWmBase> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_wm_base. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy xdg_wm_base
         *
         * Destroy this xdg_wm_base object.
         *
         * Destroying a bound xdg_wm_base object while there are surfaces
         * still alive created by this xdg_wm_base object instance is illegal
         * and will result in a defunct_surfaces error.
         */
        public fun destroy(resource: XdgWmBase)

        /**
         * Create a positioner object
         *
         * Create a positioner object. A positioner object is used to position
         * surfaces relative to some parent surface. See the interface description
         * and xdg_surface.get_popup for details.
         */
        public fun createPositioner(resource: XdgWmBase, id: XdgPositioner)

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
        public fun getXdgSurface(resource: XdgWmBase, id: XdgSurface, surface: WlSurface)

        /**
         * Respond to a ping event
         *
         * A client must respond to a ping event with a pong request or
         * the client may be deemed unresponsive. See xdg_wm_base.ping
         * and xdg_wm_base.error.unresponsive.
         */
        public fun pong(resource: XdgWmBase, serial: Int)
    }

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
    public fun sendPing(serial: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).end()
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

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_wm_base is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(XdgPositioner.create(client, idId, version))
                handler.createPositioner(this, id)
            }
            2 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(XdgSurface.create(client, idId, version))
                handler.getXdgSurface(this, id, surface)
            }
            3 -> {
                val serial = args.uint()
                args.end()
                handler.pong(this, serial)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_wm_base request $opcode")
        }
    }

    public companion object : ResourceType<XdgWmBase>("xdg_wm_base", 7) {
        internal override fun create(client: Client, id: Int, version: Int): XdgWmBase = XdgWmBase(client, id, version)
    }
}
