// Generated from relative-pointer-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.relativepointerunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlPointer

/**
 * Get relative pointer objects
 *
 * A global interface used for getting the relative pointer object for a
 * given pointer.
 */
public class ZwpRelativePointerManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpRelativePointerManagerV1> get() = Companion

    /**
     * Destroy the relative pointer manager object
     *
     * Used by the client to notify the server that it will no longer use this
     * relative pointer manager object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Get a relative pointer object
     *
     * Create a relative pointer interface given a wl_pointer object. See the
     * wp_relative_pointer interface for more details.
     */
    public fun getRelativePointer(pointer: WlPointer): ZwpRelativePointerV1 {
        val created = connection.createProxy(ZwpRelativePointerV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_relative_pointer", "id=${Wire.show(created)}, pointer=${Wire.show(pointer)}")
        request(1).newId(created).obj(pointer).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpRelativePointerManagerV1>("zwp_relative_pointer_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpRelativePointerManagerV1 = ZwpRelativePointerManagerV1(connection, id, version)
    }
}
