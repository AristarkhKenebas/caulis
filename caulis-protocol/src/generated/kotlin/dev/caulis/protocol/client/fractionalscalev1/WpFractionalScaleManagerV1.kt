// Generated from fractional-scale-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.fractionalscalev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Fractional surface scale information
 *
 * A global interface for requesting surfaces to use fractional scales.
 */
public class WpFractionalScaleManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpFractionalScaleManagerV1> get() = Companion

    /**
     * Unbind the fractional surface scale interface
     *
     * Informs the server that the client will not be using this protocol
     * object anymore. This does not affect any other objects,
     * wp_fractional_scale_v1 objects included.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Extend surface interface for scale information
     *
     * Create an add-on object for the the wl_surface to let the compositor
     * request fractional scales. If the given wl_surface already has a
     * wp_fractional_scale_v1 object associated, the fractional_scale_exists
     * protocol error is raised.
     */
    public fun getFractionalScale(surface: WlSurface): WpFractionalScaleV1 {
        val created = connection.createProxy(WpFractionalScaleV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_fractional_scale", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(1).newId(created).obj(surface).end()
        return created
    }

    public object Error {
        /** the surface already has a fractional_scale object associated */
        public const val FRACTIONAL_SCALE_EXISTS: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WpFractionalScaleManagerV1>("wp_fractional_scale_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpFractionalScaleManagerV1 = WpFractionalScaleManagerV1(connection, id, version)
    }
}
