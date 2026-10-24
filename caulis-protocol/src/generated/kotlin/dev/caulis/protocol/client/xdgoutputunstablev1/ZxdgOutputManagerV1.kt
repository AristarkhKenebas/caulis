// Generated from xdg-output-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgoutputunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Manage xdg_output objects
 *
 * A global factory interface for xdg_output objects.
 */
public class ZxdgOutputManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZxdgOutputManagerV1> get() = Companion

    /**
     * Destroy the xdg_output_manager object
     *
     * Using this request a client can tell the server that it is not
     * going to use the xdg_output_manager object anymore.
     *
     * Any objects already created through this instance are not affected.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create an xdg output from a wl_output
     *
     * This creates a new xdg_output object for the given wl_output.
     */
    public fun getXdgOutput(output: WlOutput): ZxdgOutputV1 {
        val created = connection.createProxy(ZxdgOutputV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_xdg_output", "id=${Wire.show(created)}, output=${Wire.show(output)}")
        request(1).newId(created).obj(output).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZxdgOutputManagerV1>("zxdg_output_manager_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZxdgOutputManagerV1 = ZxdgOutputManagerV1(connection, id, version)
    }
}
