// Generated from wlr-gamma-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrgammacontrolunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Manager to create per-output gamma controls
 *
 * This interface is a manager that allows creating per-output gamma
 * controls.
 */
public class ZwlrGammaControlManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrGammaControlManagerV1> get() = Companion

    /**
     * Get a gamma control for an output
     *
     * Create a gamma control that can be used to adjust gamma tables for the
     * provided output.
     */
    public fun getGammaControl(output: WlOutput): ZwlrGammaControlV1 {
        val created = connection.createProxy(ZwlrGammaControlV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_gamma_control", "id=${Wire.show(created)}, output=${Wire.show(output)}")
        request(0).newId(created).obj(output).end()
        return created
    }

    /**
     * Destroy the manager
     *
     * All objects created by the manager will still remain valid, until their
     * appropriate destroy request has been called.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwlrGammaControlManagerV1>("zwlr_gamma_control_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrGammaControlManagerV1 = ZwlrGammaControlManagerV1(connection, id, version)
    }
}
