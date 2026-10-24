// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class WlRegistry private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlRegistry> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Announce global object
         *
         * Notify the client of global objects.
         *
         * The event notifies the client that a global object with
         * the given name is now available, and it implements the
         * given version of the given interface.
         */
        public fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {}

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
        public fun globalRemove(proxy: WlRegistry, name: Int) {}
    }

    /**
     * Bind an object to the display
     *
     * Binds a new, client-created object to the server using the
     * specified name as the identifier.
     */
    public fun <T : Proxy> bind(name: Int, type: ProxyType<T>, version: Int): T {
        val created = connection.createProxy(type, version)
        request(0).uint(name).string(type.name).uint(version).newId(created).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val name = args.uint()
                val `interface` = args.string()
                val versionArg = args.uint()
                args.end()
                handler?.global(this, name, `interface`, versionArg)
            }
            1 -> {
                val name = args.uint()
                args.end()
                handler?.globalRemove(this, name)
            }
        }
    }

    public companion object : ProxyType<WlRegistry>("wl_registry", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlRegistry = WlRegistry(connection, id, version)
    }
}
