// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * The compositor singleton
 *
 * A compositor.  This object is a singleton global.  The
 * compositor is in charge of combining the contents of multiple
 * surfaces into one displayable output.
 */
public class WlCompositor private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlCompositor> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_compositor. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create new surface
         *
         * Ask the compositor to create a new surface.
         */
        public fun createSurface(resource: WlCompositor, id: WlSurface)

        /**
         * Create new region
         *
         * Ask the compositor to create a new region.
         */
        public fun createRegion(resource: WlCompositor, id: WlRegion)

        /**
         * Destroy wl_compositor
         *
         * This request destroys the wl_compositor. This has no effect on any other objects.
         *
         * Since version 7.
         */
        public fun release(resource: WlCompositor)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_compositor is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlSurface.create(client, idId, version))
                handler.createSurface(this, id)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlRegion.create(client, idId, version))
                handler.createRegion(this, id)
            }
            2 -> {
                if (version < 7) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_compositor.release needs version 7")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_compositor request $opcode")
        }
    }

    public companion object : ResourceType<WlCompositor>("wl_compositor", 7) {
        internal override fun create(client: Client, id: Int, version: Int): WlCompositor = WlCompositor(client, id, version)
    }
}
