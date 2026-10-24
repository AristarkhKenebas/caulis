// Generated from xdg-decoration-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgdecorationunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.xdgshell.XdgToplevel

/**
 * Window decoration manager
 *
 * This interface allows a compositor to announce support for server-side
 * decorations.
 *
 * A window decoration is a set of window controls as deemed appropriate by
 * the party managing them, such as user interface components used to move,
 * resize and change a window's state.
 *
 * A client can use this protocol to request being decorated by a supporting
 * compositor.
 *
 * If compositor and client do not negotiate the use of a server-side
 * decoration using this protocol, clients continue to self-decorate as they
 * see fit.
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
public class ZxdgDecorationManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZxdgDecorationManagerV1> get() = Companion

    /**
     * Destroy the decoration manager object
     *
     * Destroy the decoration manager. This doesn't destroy objects created
     * with the manager.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a new toplevel decoration object
     *
     * Create a new decoration object associated with the given toplevel.
     *
     * For objects of version 1, creating an xdg_toplevel_decoration from an
     * xdg_toplevel which has a buffer attached or committed is a client
     * error, and any attempts by a client to attach or manipulate a buffer
     * prior to the first xdg_toplevel_decoration.configure event must also be
     * treated as errors.
     *
     * For objects of version 2 or newer, creating an xdg_toplevel_decoration
     * from an xdg_toplevel which has a buffer attached or committed is
     * allowed. The initial decoration mode of the surface if a buffer is
     * already attached depends on whether a xdg_toplevel_decoration object
     * has been associated with the surface or not prior to this request.
     *
     * If an xdg_toplevel_decoration was associated with the surface, then
     * destroyed without a surface commit, the previous decoration mode is
     * retained.
     *
     * If no xdg_toplevel_decoration was associated with the surface prior to
     * this request, or if a surface commit has been performed after a previous
     * xdg_toplevel_decoration object associated with the surface was
     * destroyed, the decoration mode is assumed to be client-side.
     */
    public fun getToplevelDecoration(toplevel: XdgToplevel): ZxdgToplevelDecorationV1 {
        val created = connection.createProxy(ZxdgToplevelDecorationV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_toplevel_decoration", "id=${Wire.show(created)}, toplevel=${Wire.show(toplevel)}")
        request(1).newId(created).obj(toplevel).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZxdgDecorationManagerV1>("zxdg_decoration_manager_v1", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZxdgDecorationManagerV1 = ZxdgDecorationManagerV1(connection, id, version)
    }
}
