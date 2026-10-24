// Generated from ext-foreign-toplevel-list-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extforeigntoplevellistv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * A mapped toplevel
 *
 * A ext_foreign_toplevel_handle_v1 object represents a mapped toplevel
 * window. A single app may have multiple mapped toplevels.
 */
public class ExtForeignToplevelHandleV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtForeignToplevelHandleV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_foreign_toplevel_handle_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the ext_foreign_toplevel_handle_v1 object
         *
         * This request should be used when the client will no longer use the handle
         * or after the closed event has been received to allow destruction of the
         * object.
         *
         * When a handle is destroyed, a new handle may not be created by the server
         * until the toplevel is unmapped and then remapped. Destroying a toplevel handle
         * is not recommended unless the client is cleaning up child objects
         * before destroying the ext_foreign_toplevel_list_v1 object, the toplevel
         * was closed or the toplevel handle will not be used in the future.
         *
         * Other protocols which extend the ext_foreign_toplevel_handle_v1
         * interface should require destructors for extension interfaces be
         * called before allowing the toplevel handle to be destroyed.
         */
        public fun destroy(resource: ExtForeignToplevelHandleV1)
    }

    /**
     * The toplevel has been closed
     *
     * The server will emit no further events on the ext_foreign_toplevel_handle_v1
     * after this event. Any requests received aside from the destroy request must
     * be ignored. Upon receiving this event, the client should destroy the handle.
     *
     * Other protocols which extend the ext_foreign_toplevel_handle_v1
     * interface must also ignore requests other than destructors.
     */
    public fun sendClosed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "closed", "")
        event(0).end()
    }

    /**
     * All information about the toplevel has been sent
     *
     * This event is sent after all changes in the toplevel state have
     * been sent.
     *
     * This allows changes to the ext_foreign_toplevel_handle_v1 properties
     * to be atomically applied. Other protocols which extend the
     * ext_foreign_toplevel_handle_v1 interface may use this event to also
     * atomically apply any pending state.
     *
     * This event must not be sent after the ext_foreign_toplevel_handle_v1.closed
     * event.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "")
        event(1).end()
    }

    /**
     * Title change
     *
     * The title of the toplevel has changed.
     *
     * The configured state must not be applied immediately. See
     * ext_foreign_toplevel_handle_v1.done for details.
     */
    public fun sendTitle(title: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "title", "title=${Wire.show(title)}")
        event(2).string(title).end()
    }

    /**
     * App_id change
     *
     * The app id of the toplevel has changed.
     *
     * The configured state must not be applied immediately. See
     * ext_foreign_toplevel_handle_v1.done for details.
     */
    public fun sendAppId(appId: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "app_id", "app_id=${Wire.show(appId)}")
        event(3).string(appId).end()
    }

    /**
     * A stable identifier for a toplevel
     *
     * This identifier is used to check if two or more toplevel handles belong
     * to the same toplevel.
     *
     * The identifier is useful for command line tools or privileged clients
     * which may need to reference an exact toplevel across processes or
     * instances of the ext_foreign_toplevel_list_v1 global.
     *
     * The compositor must only send this event when the handle is created.
     *
     * The identifier must be unique per toplevel and its handles. Two different
     * toplevels must not have the same identifier. The identifier is only valid
     * as long as the toplevel is mapped. If the toplevel is unmapped the identifier
     * must not be reused. An identifier must not be reused by the compositor to
     * ensure there are no races when sharing identifiers between processes.
     *
     * An identifier is a string that contains up to 32 printable ASCII bytes.
     * An identifier must not be an empty string. It is recommended that a
     * compositor includes an opaque generation value in identifiers. How the
     * generation value is used when generating the identifier is implementation
     * dependent.
     */
    public fun sendIdentifier(identifier: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "identifier", "identifier=${Wire.show(identifier)}")
        event(4).string(identifier).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_foreign_toplevel_handle_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_foreign_toplevel_handle_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtForeignToplevelHandleV1>("ext_foreign_toplevel_handle_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtForeignToplevelHandleV1 = ExtForeignToplevelHandleV1(client, id, version)
    }
}
