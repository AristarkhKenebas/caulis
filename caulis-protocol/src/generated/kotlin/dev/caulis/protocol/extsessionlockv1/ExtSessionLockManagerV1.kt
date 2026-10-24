// Generated from ext-session-lock-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extsessionlockv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Used to lock the session
 *
 * This interface is used to request that the session be locked.
 */
public class ExtSessionLockManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtSessionLockManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_session_lock_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the session lock manager object
         *
         * This informs the compositor that the session lock manager object will
         * no longer be used. Existing objects created through this interface
         * remain valid.
         */
        public fun destroy(resource: ExtSessionLockManagerV1)

        /**
         * Attempt to lock the session
         *
         * This request creates a session lock and asks the compositor to lock the
         * session. The compositor will send either the ext_session_lock_v1.locked
         * or ext_session_lock_v1.finished event on the created object in
         * response to this request.
         */
        public fun lock(resource: ExtSessionLockManagerV1, id: ExtSessionLockV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_session_lock_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(ExtSessionLockV1.create(client, idId, version))
                handler.lock(this, id)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_session_lock_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtSessionLockManagerV1>("ext_session_lock_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtSessionLockManagerV1 = ExtSessionLockManagerV1(client, id, version)
    }
}
