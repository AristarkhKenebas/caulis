// Generated from server-decoration.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.serverdecoration

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

public class OrgKdeKwinServerDecoration private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<OrgKdeKwinServerDecoration> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun mode(proxy: OrgKdeKwinServerDecoration, mode: Int) {}
    }

    /**
     * Release the server decoration object
     */
    public fun release(): Unit {
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * The decoration mode the surface wants to use.
     */
    public fun requestMode(mode: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "request_mode", "mode=${Wire.show(mode)}")
        request(1).uint(mode).end()
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
                if (Wire.tracing) Wire.received(this, "mode", "mode=${Wire.show(mode)}")
                handler?.mode(this, mode)
            }
        }
    }

    public companion object : ProxyType<OrgKdeKwinServerDecoration>("org_kde_kwin_server_decoration", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): OrgKdeKwinServerDecoration = OrgKdeKwinServerDecoration(connection, id, version)
    }
}
