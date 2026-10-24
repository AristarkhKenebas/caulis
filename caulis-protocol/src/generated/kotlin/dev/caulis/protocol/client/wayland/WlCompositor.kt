// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * The compositor singleton
 *
 * A compositor.  This object is a singleton global.  The
 * compositor is in charge of combining the contents of multiple
 * surfaces into one displayable output.
 */
public class WlCompositor private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlCompositor> get() = Companion

    /**
     * Create new surface
     *
     * Ask the compositor to create a new surface.
     */
    public fun createSurface(): WlSurface {
        val created = connection.createProxy(WlSurface, this.version)
        request(0).newId(created).end()
        return created
    }

    /**
     * Create new region
     *
     * Ask the compositor to create a new region.
     */
    public fun createRegion(): WlRegion {
        val created = connection.createProxy(WlRegion, this.version)
        request(1).newId(created).end()
        return created
    }

    /**
     * Destroy wl_compositor
     *
     * This request destroys the wl_compositor. This has no effect on any other objects.
     *
     * Since version 7.
     */
    public fun release(): Unit {
        check(version >= 7) { "wl_compositor.release needs version 7, this object has $version" }
        request(2).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlCompositor>("wl_compositor", 7) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlCompositor = WlCompositor(connection, id, version)
    }
}
