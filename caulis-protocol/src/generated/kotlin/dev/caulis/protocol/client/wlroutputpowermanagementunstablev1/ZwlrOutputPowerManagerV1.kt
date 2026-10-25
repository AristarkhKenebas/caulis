// Generated from wlr-output-power-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputpowermanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Manager to create per-output power management
 *
 * This interface is a manager that allows creating per-output power
 * management mode controls.
 */
public class ZwlrOutputPowerManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputPowerManagerV1> get() = Companion

    /**
     * Get a power management for an output
     *
     * Create an output power management mode control that can be used to
     * adjust the power management mode for a given output.
     */
    public fun getOutputPower(output: WlOutput): ZwlrOutputPowerV1 {
        val created = connection.createProxy(ZwlrOutputPowerV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_output_power", "id=${Wire.show(created)}, output=${Wire.show(output)}")
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

    public companion object : ProxyType<ZwlrOutputPowerManagerV1>("zwlr_output_power_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputPowerManagerV1 = ZwlrOutputPowerManagerV1(connection, id, version)
    }
}
