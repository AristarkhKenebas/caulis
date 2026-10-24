// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Create desktop-style surfaces
 *
 * This interface is implemented by servers that provide
 * desktop-style user interfaces.
 *
 * It allows clients to associate a wl_shell_surface with
 * a basic surface.
 *
 * Note! This protocol is deprecated and not intended for production use.
 * For desktop-style user interfaces, use xdg_shell. Compositors and clients
 * should not implement this interface.
 */
public class WlShell private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlShell> get() = Companion

    /**
     * Create a shell surface from a surface
     *
     * Create a shell surface for an existing surface. This gives
     * the wl_surface the role of a shell surface. If the wl_surface
     * already has another role, it raises a protocol error.
     *
     * Only one shell surface can be associated with a given surface.
     */
    public fun getShellSurface(surface: WlSurface): WlShellSurface {
        val created = connection.createProxy(WlShellSurface, this.version)
        if (Wire.tracing) Wire.sent(this, "get_shell_surface", "id=${Wire.show(created)}, surface=${Wire.show(surface)}")
        request(0).newId(created).obj(surface).end()
        return created
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WlShell>("wl_shell", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlShell = WlShell(connection, id, version)
    }
}
