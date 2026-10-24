// Generated from server-decoration.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.serverdecoration

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class OrgKdeKwinServerDecorationManager private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<OrgKdeKwinServerDecorationManager> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun defaultMode(proxy: OrgKdeKwinServerDecorationManager, mode: Int) {}
    }

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
    public fun create(surface: WlSurface): OrgKdeKwinServerDecoration {
        val created = connection.createProxy(OrgKdeKwinServerDecoration, this.version)
        if (Wire.tracing) Wire.sent(this, "create", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(0).newId(created).obj(surface).end()
        return created
    }

    /** Possible values to use in request_mode and the event mode. */
    public object Mode {
        /** Undecorated: The surface is not decorated at all, neither server nor client-side. An example is a popup surface which should not be decorated. */
        public const val NONE: Int = 0
        /** Client-side decoration: The decoration is part of the surface and the client. */
        public const val CLIENT: Int = 1
        /** Server-side decoration: The server embeds the surface into a decoration frame. */
        public const val SERVER: Int = 2
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "default_mode", "mode=${Wire.show(mode)}")
                handler?.defaultMode(this, mode)
            }
        }
    }

    public companion object : ProxyType<OrgKdeKwinServerDecorationManager>("org_kde_kwin_server_decoration_manager", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): OrgKdeKwinServerDecorationManager = OrgKdeKwinServerDecorationManager(connection, id, version)
    }
}
