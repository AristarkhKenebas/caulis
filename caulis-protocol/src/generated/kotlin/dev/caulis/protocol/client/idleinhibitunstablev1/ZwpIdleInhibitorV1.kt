// Generated from idle-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.idleinhibitunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Context object for inhibiting idle behavior
 *
 * An idle inhibitor prevents the output that the associated surface is
 * visible on from being set to a state where it is not visually usable due
 * to lack of user interaction (e.g. blanked, dimmed, locked, set to power
 * save, etc.)  Any screensaver processes are also blocked from displaying.
 *
 * If the surface is destroyed, unmapped, becomes occluded, loses
 * visibility, or otherwise becomes not visually relevant for the user, the
 * idle inhibitor will not be honored by the compositor; if the surface
 * subsequently regains visibility the inhibitor takes effect once again.
 * Likewise, the inhibitor isn't honored if the system was already idled at
 * the time the inhibitor was established, although if the system later
 * de-idles and re-idles the inhibitor will take effect.
 */
public class ZwpIdleInhibitorV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpIdleInhibitorV1> get() = Companion

    /**
     * Destroy the idle inhibitor object
     *
     * Remove the inhibitor effect from the associated wl_surface.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpIdleInhibitorV1>("zwp_idle_inhibitor_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpIdleInhibitorV1 = ZwpIdleInhibitorV1(connection, id, version)
    }
}
