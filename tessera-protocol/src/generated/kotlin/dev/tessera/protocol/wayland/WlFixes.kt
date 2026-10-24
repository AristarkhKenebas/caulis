// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Wayland protocol fixes
 *
 * This global fixes problems with other core-protocol interfaces that
 * cannot be fixed in these interfaces themselves.
 */
public class WlFixes private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlFixes> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_fixes. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroys this object
         */
        public fun destroy(resource: WlFixes)

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
        public fun destroyRegistry(resource: WlFixes, registry: WlRegistry)

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
        public fun ackGlobalRemove(resource: WlFixes, registry: WlRegistry, name: Int)
    }

    /**
     * Wl_fixes error values
     */
    public object Error {
        /** unknown global or the global is not removed */
        public const val INVALID_ACK_REMOVE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_fixes is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val registry = args.obj(WlRegistry::class.java)
                args.end()
                handler.destroyRegistry(this, registry)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_fixes.ack_global_remove needs version 2")
                val registry = args.obj(WlRegistry::class.java)
                val name = args.uint()
                args.end()
                handler.ackGlobalRemove(this, registry, name)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_fixes request $opcode")
        }
    }

    public companion object : ResourceType<WlFixes>("wl_fixes", 2) {
        internal override fun create(client: Client, id: Int, version: Int): WlFixes = WlFixes(client, id, version)
    }
}
