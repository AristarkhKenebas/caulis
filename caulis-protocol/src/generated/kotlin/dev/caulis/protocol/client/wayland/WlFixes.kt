// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Wayland protocol fixes
 *
 * This global fixes problems with other core-protocol interfaces that
 * cannot be fixed in these interfaces themselves.
 */
public class WlFixes private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlFixes> get() = Companion

    /**
     * Destroys this object
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Destroy a wl_registry
     *
     * This request destroys a wl_registry object.
     *
     * The client should no longer use the wl_registry after making this
     * request.
     *
     * The compositor will emit a wl_display.delete_id event with the object ID
     * of the registry and will no longer emit any events on the registry. The
     * client should re-use the object ID once it receives the
     * wl_display.delete_id event.
     */
    public fun destroyRegistry(registry: WlRegistry): Unit {
        request(1).obj(registry).end()
    }

    /**
     * Acknowledge global removal
     *
     * Acknowledge the removal of the specified global.
     *
     * If no global with the specified name exists or the global is not removed,
     * the wl_fixes.invalid_ack_remove protocol error will be posted.
     *
     * Due to the Wayland protocol being asynchronous, the wl_global objects
     * cannot be destroyed immediately. For example, if a wl_global is removed
     * and a client attempts to bind that global around same time, it can
     * result in a protocol error due to an unknown global name in the bind
     * request.
     *
     * In order to avoid crashing clients, the compositor should remove the
     * wl_global once it is guaranteed that no more bind requests will come.
     *
     * The wl_fixes.ack_global_remove() request is used to signal to the
     * compositor that the client will not bind the given global anymore. After
     * all clients acknowledge the removal of the global, the compositor can
     * safely destroy it.
     *
     * The client must call the wl_fixes.ack_global_remove() request in
     * response to a wl_registry.global_remove() event even if it did not bind
     * the corresponding global.
     *
     * Since version 2.
     */
    public fun ackGlobalRemove(registry: WlRegistry, name: Int): Unit {
        check(version >= 2) { "wl_fixes.ack_global_remove needs version 2, this object has $version" }
        request(2).obj(registry).uint(name).end()
    }

    /** wl_fixes error values */
    public object Error {
        /** unknown global or the global is not removed */
        public const val INVALID_ACK_REMOVE: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlFixes>("wl_fixes", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlFixes = WlFixes(connection, id, version)
    }
}
