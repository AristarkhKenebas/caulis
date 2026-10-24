// Generated from ext-foreign-toplevel-list-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extforeigntoplevellistv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * A mapped toplevel
 *
 * A ext_foreign_toplevel_handle_v1 object represents a mapped toplevel
 * window. A single app may have multiple mapped toplevels.
 */
public class ExtForeignToplevelHandleV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtForeignToplevelHandleV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun closed(proxy: ExtForeignToplevelHandleV1) {}

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
        public fun done(proxy: ExtForeignToplevelHandleV1) {}

        /**
         * Title change
         *
         * The title of the toplevel has changed.
         *
         * The configured state must not be applied immediately. See
         * ext_foreign_toplevel_handle_v1.done for details.
         */
        public fun title(proxy: ExtForeignToplevelHandleV1, title: String) {}

        /**
         * App_id change
         *
         * The app id of the toplevel has changed.
         *
         * The configured state must not be applied immediately. See
         * ext_foreign_toplevel_handle_v1.done for details.
         */
        public fun appId(proxy: ExtForeignToplevelHandleV1, appId: String) {}

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
        public fun identifier(proxy: ExtForeignToplevelHandleV1, identifier: String) {}
    }

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
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "closed", "")
                handler?.closed(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            2 -> {
                val title = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "title", "title=${Wire.show(title)}")
                handler?.title(this, title)
            }
            3 -> {
                val appId = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "app_id", "app_id=${Wire.show(appId)}")
                handler?.appId(this, appId)
            }
            4 -> {
                val identifier = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "identifier", "identifier=${Wire.show(identifier)}")
                handler?.identifier(this, identifier)
            }
        }
    }

    public companion object : ProxyType<ExtForeignToplevelHandleV1>("ext_foreign_toplevel_handle_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtForeignToplevelHandleV1 = ExtForeignToplevelHandleV1(connection, id, version)
    }
}
