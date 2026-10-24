// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Region interface
 *
 * A region object describes an area.
 *
 * Region objects are used to describe the opaque and input
 * regions of a surface.
 */
public class WlRegion private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlRegion> get() = Companion

    /**
     * Destroy region
     *
     * Destroy the region.  This will invalidate the object ID.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Add rectangle to region
     *
     * Add the specified rectangle to the region.
     */
    public fun add(x: Int, y: Int, width: Int, height: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "add", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        request(1).int(x).int(y).int(width).int(height).end()
    }

    /**
     * Subtract rectangle from region
     *
     * Subtract the specified rectangle from the region.
     */
    public fun subtract(x: Int, y: Int, width: Int, height: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "subtract", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        request(2).int(x).int(y).int(width).int(height).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlRegion>("wl_region", 7) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlRegion = WlRegion(connection, id, version)
    }
}
