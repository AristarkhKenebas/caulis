// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

/**
 * Global registry object
 *
 * The singleton global registry object.  The server has a number of
 * global objects that are available to all clients.  These objects
 * typically represent an actual object in the server (for example,
 * an input device) or they are singleton objects that provide
 * extension functionality.
 *
 * When a client creates a registry object, the registry object
 * will emit a global event for each global currently in the
 * registry.  Globals come and go as a result of device or
 * monitor hotplugs, reconfiguration or other events, and the
 * registry will send out global and global_remove events to
 * keep the client up to date with the changes.  To mark the end
 * of the initial burst of events, the client can use the
 * wl_display.sync request immediately after calling
 * wl_display.get_registry.
 *
 * A client can bind to a global object by using the bind
 * request.  This creates a client-side handle that lets the object
 * emit events to the client and lets the client invoke requests on
 * the object.
 */
public class WlRegistry private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlRegistry> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_registry. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Bind an object to the display
         *
         * Binds a new, client-created object to the server using the
         * specified name as the identifier.
         */
        public fun bind(resource: WlRegistry, name: Int, interfaceName: String, version: Int, id: Int)
    }

    /**
     * Announce global object
     *
     * Notify the client of global objects.
     *
     * The event notifies the client that a global object with
     * the given name is now available, and it implements the
     * given version of the given interface.
     */
    public fun sendGlobal(name: Int, `interface`: String, version: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(name).string(`interface`).uint(version).end()
    }

    /**
     * Announce removal of global object
     *
     * Notify the client of removed global objects.
     *
     * This event notifies the client that the global identified
     * by name is no longer available.  If the client bound to
     * the global using the bind request, the client should now
     * destroy that object.
     *
     * The object remains valid and requests to the object will be
     * ignored until the client destroys it, to avoid races between
     * the global going away and a client sending a request to it.
     */
    public fun sendGlobalRemove(name: Int) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(name).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_registry is not implemented")
        when (opcode) {
            0 -> {
                val name = args.uint()
                val interfaceName = args.string()
                val requestedVersion = args.uint()
                val id = args.newId()
                args.end()
                handler.bind(this, name, interfaceName, requestedVersion, id)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_registry request $opcode")
        }
    }

    public companion object : ResourceType<WlRegistry>("wl_registry", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlRegistry = WlRegistry(client, id, version)
    }
}
