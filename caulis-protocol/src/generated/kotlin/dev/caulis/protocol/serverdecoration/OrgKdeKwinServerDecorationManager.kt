// Generated from server-decoration.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.serverdecoration

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * Server side window decoration manager
 *
 * This interface allows to coordinate whether the server should create
 * a server-side window decoration around a wl_surface representing a
 * shell surface (wl_shell_surface or similar). By announcing support
 * for this interface the server indicates that it supports server
 * side decorations.
 *
 * Use in conjunction with zxdg_decoration_manager_v1 is undefined.
 */
public class OrgKdeKwinServerDecorationManager private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<OrgKdeKwinServerDecorationManager> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements org_kde_kwin_server_decoration_manager. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a server-side decoration object for a given surface
         *
         * When a client creates a server-side decoration object it indicates
         * that it supports the protocol. The client is supposed to tell the
         * server whether it wants server-side decorations or will provide
         * client-side decorations.
         *
         * If the client does not create a server-side decoration object for
         * a surface the server interprets this as lack of support for this
         * protocol and considers it as client-side decorated. Nevertheless a
         * client-side decorated surface should use this protocol to indicate
         * to the server that it does not want a server-side deco.
         */
        public fun create(resource: OrgKdeKwinServerDecorationManager, id: OrgKdeKwinServerDecoration, surface: WlSurface)
    }

    /**
     * The default mode used on the server
     *
     * This event is emitted directly after binding the interface. It contains
     * the default mode for the decoration. When a new server decoration object
     * is created this new object will be in the default mode until the first
     * request_mode is requested.
     *
     * The server may change the default mode at any time.
     */
    public fun sendDefaultMode(mode: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "default_mode", "mode=${Wire.show(mode)}")
        event(0).uint(mode).end()
    }

    /**
     * Possible values to use in request_mode and the event mode.
     */
    public object Mode {
        /** Undecorated: The surface is not decorated at all, neither server nor client-side. An example is a popup surface which should not be decorated. */
        public const val NONE: Int = 0
        /** Client-side decoration: The decoration is part of the surface and the client. */
        public const val CLIENT: Int = 1
        /** Server-side decoration: The server embeds the surface into a decoration frame. */
        public const val SERVER: Int = 2
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "org_kde_kwin_server_decoration_manager is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(OrgKdeKwinServerDecoration.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "create", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.create(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown org_kde_kwin_server_decoration_manager request $opcode")
        }
    }

    public companion object : ResourceType<OrgKdeKwinServerDecorationManager>("org_kde_kwin_server_decoration_manager", 1) {
        internal override fun create(client: Client, id: Int, version: Int): OrgKdeKwinServerDecorationManager = OrgKdeKwinServerDecorationManager(client, id, version)
    }
}
