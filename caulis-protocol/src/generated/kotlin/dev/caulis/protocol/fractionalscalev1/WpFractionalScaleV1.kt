// Generated from fractional-scale-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.fractionalscalev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Fractional scale interface to a wl_surface
 *
 * An additional interface to a wl_surface object which allows the compositor
 * to inform the client of the preferred scale.
 */
public class WpFractionalScaleV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpFractionalScaleV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_fractional_scale_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Remove surface scale information for surface
         *
         * Destroy the fractional scale object. When this object is destroyed,
         * preferred_scale events will no longer be sent.
         */
        public fun destroy(resource: WpFractionalScaleV1)
    }

    /**
     * Notify of new preferred scale
     *
     * Notification of a new preferred scale for this surface that the
     * compositor suggests that the client should use.
     *
     * The sent scale is the numerator of a fraction with a denominator of 120.
     */
    public fun sendPreferredScale(scale: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "preferred_scale", "scale=${Wire.show(scale)}")
        event(0).uint(scale).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_fractional_scale_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_fractional_scale_v1 request $opcode")
        }
    }

    public companion object : ResourceType<WpFractionalScaleV1>("wp_fractional_scale_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WpFractionalScaleV1 = WpFractionalScaleV1(client, id, version)
    }
}
