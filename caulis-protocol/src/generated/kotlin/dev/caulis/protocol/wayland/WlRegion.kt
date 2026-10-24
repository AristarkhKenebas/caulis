// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

/**
 * Region interface
 *
 * A region object describes an area.
 *
 * Region objects are used to describe the opaque and input
 * regions of a surface.
 */
public class WlRegion private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlRegion> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_region. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy region
         *
         * Destroy the region.  This will invalidate the object ID.
         */
        public fun destroy(resource: WlRegion)

        /**
         * Add rectangle to region
         *
         * Add the specified rectangle to the region.
         */
        public fun add(resource: WlRegion, x: Int, y: Int, width: Int, height: Int)

        /**
         * Subtract rectangle from region
         *
         * Subtract the specified rectangle from the region.
         */
        public fun subtract(resource: WlRegion, x: Int, y: Int, width: Int, height: Int)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_region is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                handler.add(this, x, y, width, height)
            }
            2 -> {
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                handler.subtract(this, x, y, width, height)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_region request $opcode")
        }
    }

    public companion object : ResourceType<WlRegion>("wl_region", 7) {
        internal override fun create(client: Client, id: Int, version: Int): WlRegion = WlRegion(client, id, version)
    }
}
