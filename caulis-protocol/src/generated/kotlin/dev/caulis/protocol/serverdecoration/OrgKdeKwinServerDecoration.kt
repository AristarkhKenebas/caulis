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

public class OrgKdeKwinServerDecoration private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<OrgKdeKwinServerDecoration> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements org_kde_kwin_server_decoration. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Release the server decoration object
         */
        public fun release(resource: OrgKdeKwinServerDecoration)

        /**
         * The decoration mode the surface wants to use.
         */
        public fun requestMode(resource: OrgKdeKwinServerDecoration, mode: Int)
    }

    /**
     * The new decoration mode applied by the server
     *
     * This event is emitted directly after the decoration is created and
     * represents the base decoration policy by the server. E.g. a server
     * which wants all surfaces to be client-side decorated will send Client,
     * a server which wants server-side decoration will send Server.
     *
     * The client can request a different mode through the decoration request.
     * The server will acknowledge this by another event with the same mode. So
     * even if a server prefers server-side decoration it's possible to force a
     * client-side decoration.
     *
     * The server may emit this event at any time. In this case the client can
     * again request a different mode. It's the responsibility of the server to
     * prevent a feedback loop.
     */
    public fun sendMode(mode: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "mode", "mode=${Wire.show(mode)}")
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
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "org_kde_kwin_server_decoration is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "release", "")
                handler.release(this)
                client.destroyResource(this)
            }
            1 -> {
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "request_mode", "mode=${Wire.show(mode)}")
                handler.requestMode(this, mode)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown org_kde_kwin_server_decoration request $opcode")
        }
    }

    public companion object : ResourceType<OrgKdeKwinServerDecoration>("org_kde_kwin_server_decoration", 1) {
        internal override fun create(client: Client, id: Int, version: Int): OrgKdeKwinServerDecoration = OrgKdeKwinServerDecoration(client, id, version)
    }
}
