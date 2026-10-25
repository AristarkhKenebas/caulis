// Generated from idle-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.idleinhibitunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Control behavior when display idles
 *
 * This interface permits inhibiting the idle behavior such as screen
 * blanking, locking, and screensaving.  The client binds the idle manager
 * globally, then creates idle-inhibitor objects for each surface.
 *
 * Warning! The protocol described in this file is experimental and
 * backward incompatible changes may be made. Backward compatible changes
 * may be added together with the corresponding interface version bump.
 * Backward incompatible changes are done by bumping the version number in
 * the protocol and interface names and resetting the interface version.
 * Once the protocol is to be declared stable, the 'z' prefix and the
 * version number in the protocol and interface names are removed and the
 * interface version number is reset.
 */
public class ZwpIdleInhibitManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpIdleInhibitManagerV1> get() = Companion

    /**
     * Destroy the idle inhibitor object
     *
     * Destroy the inhibit manager.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a new inhibitor object
     *
     * Create a new inhibitor object associated with the given surface.
     */
    public fun createInhibitor(surface: WlSurface): ZwpIdleInhibitorV1 {
        val created = connection.createProxy(ZwpIdleInhibitorV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_inhibitor", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(1).newId(created).obj(surface).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpIdleInhibitManagerV1>("zwp_idle_inhibit_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpIdleInhibitManagerV1 = ZwpIdleInhibitManagerV1(connection, id, version)
    }
}
