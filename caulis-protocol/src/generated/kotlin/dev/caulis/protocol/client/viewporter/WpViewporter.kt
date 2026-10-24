// Generated from viewporter.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.viewporter

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Surface cropping and scaling
 *
 * The global interface exposing surface cropping and scaling
 * capabilities is used to instantiate an interface extension for a
 * wl_surface object. This extended interface will then allow
 * cropping and scaling the surface contents, effectively
 * disconnecting the direct relationship between the buffer and the
 * surface size.
 */
public class WpViewporter private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpViewporter> get() = Companion

    /**
     * Unbind from the cropping and scaling interface
     *
     * Informs the server that the client will not be using this
     * protocol object anymore. This does not affect any other objects,
     * wp_viewport objects included.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Extend surface interface for crop and scale
     *
     * Instantiate an interface extension for the given wl_surface to
     * crop and scale its content. If the given wl_surface already has
     * a wp_viewport object associated, the viewport_exists
     * protocol error is raised.
     */
    public fun getViewport(surface: WlSurface): WpViewport {
        val created = connection.createProxy(WpViewport, this.version)
        if (Wire.tracing) Wire.sent(this, "get_viewport", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(1).newId(created).obj(surface).end()
        return created
    }

    public object Error {
        /** the surface already has a viewport object associated */
        public const val VIEWPORT_EXISTS: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WpViewporter>("wp_viewporter", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpViewporter = WpViewporter(connection, id, version)
    }
}
