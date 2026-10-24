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
import dev.caulis.protocol.wayland.WlSurface

/**
 * Fractional surface scale information
 *
 * A global interface for requesting surfaces to use fractional scales.
 */
public class WpFractionalScaleManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpFractionalScaleManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_fractional_scale_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Unbind the fractional surface scale interface
         *
         * Informs the server that the client will not be using this protocol
         * object anymore. This does not affect any other objects,
         * wp_fractional_scale_v1 objects included.
         */
        public fun destroy(resource: WpFractionalScaleManagerV1)

        /**
         * Extend surface interface for scale information
         *
         * Create an add-on object for the the wl_surface to let the compositor
         * request fractional scales. If the given wl_surface already has a
         * wp_fractional_scale_v1 object associated, the fractional_scale_exists
         * protocol error is raised.
         */
        public fun getFractionalScale(resource: WpFractionalScaleManagerV1, id: WpFractionalScaleV1, surface: WlSurface)
    }

    public object Error {
        /** the surface already has a fractional_scale object associated */
        public const val FRACTIONAL_SCALE_EXISTS: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_fractional_scale_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(WpFractionalScaleV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_fractional_scale", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.getFractionalScale(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_fractional_scale_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<WpFractionalScaleManagerV1>("wp_fractional_scale_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WpFractionalScaleManagerV1 = WpFractionalScaleManagerV1(client, id, version)
    }
}
