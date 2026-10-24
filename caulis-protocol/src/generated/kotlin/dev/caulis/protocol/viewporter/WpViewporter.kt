// Generated from viewporter.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.viewporter

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

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
public class WpViewporter private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpViewporter> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_viewporter. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Unbind from the cropping and scaling interface
         *
         * Informs the server that the client will not be using this
         * protocol object anymore. This does not affect any other objects,
         * wp_viewport objects included.
         */
        public fun destroy(resource: WpViewporter)

        /**
         * Extend surface interface for crop and scale
         *
         * Instantiate an interface extension for the given wl_surface to
         * crop and scale its content. If the given wl_surface already has
         * a wp_viewport object associated, the viewport_exists
         * protocol error is raised.
         */
        public fun getViewport(resource: WpViewporter, id: WpViewport, surface: WlSurface)
    }

    public object Error {
        /** the surface already has a viewport object associated */
        public const val VIEWPORT_EXISTS: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_viewporter is not implemented")
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
                val id = client.register(WpViewport.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_viewport", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.getViewport(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_viewporter request $opcode")
        }
    }

    public companion object : ResourceType<WpViewporter>("wp_viewporter", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WpViewporter = WpViewporter(client, id, version)
    }
}
