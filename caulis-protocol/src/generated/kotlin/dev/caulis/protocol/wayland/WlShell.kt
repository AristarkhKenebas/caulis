// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire

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
public class WlShell private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlShell> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_shell. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a shell surface from a surface
         *
         * Create a shell surface for an existing surface. This gives
         * the wl_surface the role of a shell surface. If the wl_surface
         * already has another role, it raises a protocol error.
         *
         * Only one shell surface can be associated with a given surface.
         */
        public fun getShellSurface(resource: WlShell, id: WlShellSurface, surface: WlSurface)
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_shell is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(WlShellSurface.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_shell_surface", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.getShellSurface(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_shell request $opcode")
        }
    }

    public companion object : ResourceType<WlShell>("wl_shell", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlShell = WlShell(client, id, version)
    }
}
