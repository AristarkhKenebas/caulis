// Generated from fractional-scale-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.fractionalscalev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Fractional scale interface to a wl_surface
 *
 * An additional interface to a wl_surface object which allows the compositor
 * to inform the client of the preferred scale.
 */
public class WpFractionalScaleV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpFractionalScaleV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Notify of new preferred scale
         *
         * Notification of a new preferred scale for this surface that the
         * compositor suggests that the client should use.
         *
         * The sent scale is the numerator of a fraction with a denominator of 120.
         */
        public fun preferredScale(proxy: WpFractionalScaleV1, scale: Int) {}
    }

    /**
     * Remove surface scale information for surface
     *
     * Destroy the fractional scale object. When this object is destroyed,
     * preferred_scale events will no longer be sent.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val scale = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "preferred_scale", "scale=${Wire.show(scale)}")
                handler?.preferredScale(this, scale)
            }
        }
    }

    public companion object : ProxyType<WpFractionalScaleV1>("wp_fractional_scale_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpFractionalScaleV1 = WpFractionalScaleV1(connection, id, version)
    }
}
