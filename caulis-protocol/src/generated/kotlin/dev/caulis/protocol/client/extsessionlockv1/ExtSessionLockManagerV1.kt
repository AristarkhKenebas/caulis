// Generated from ext-session-lock-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extsessionlockv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Used to lock the session
 *
 * This interface is used to request that the session be locked.
 */
public class ExtSessionLockManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtSessionLockManagerV1> get() = Companion

    /**
     * Destroy the session lock manager object
     *
     * This informs the compositor that the session lock manager object will
     * no longer be used. Existing objects created through this interface
     * remain valid.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Attempt to lock the session
     *
     * This request creates a session lock and asks the compositor to lock the
     * session. The compositor will send either the ext_session_lock_v1.locked
     * or ext_session_lock_v1.finished event on the created object in
     * response to this request.
     */
    public fun lock(): ExtSessionLockV1 {
        val created = connection.createProxy(ExtSessionLockV1, this.version)
        if (Wire.tracing) Wire.sent(this, "lock", "id=${Wire.show(created)}")
        request(1).newId(created).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtSessionLockManagerV1>("ext_session_lock_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtSessionLockManagerV1 = ExtSessionLockManagerV1(connection, id, version)
    }
}
