// Generated from wlr-foreign-toplevel-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrforeigntoplevelmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * List and control opened apps
 *
 * The purpose of this protocol is to enable the creation of taskbars
 * and docks by providing them with a list of opened applications and
 * letting them request certain actions on them, like maximizing, etc.
 *
 * After a client binds the zwlr_foreign_toplevel_manager_v1, each opened
 * toplevel window will be sent via the toplevel event
 */
public class ZwlrForeignToplevelManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrForeignToplevelManagerV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * A toplevel has been created
         *
         * This event is emitted whenever a new toplevel window is created. It
         * is emitted for all toplevels, regardless of the app that has created
         * them.
         *
         * All initial details of the toplevel(title, app_id, states, etc.) will
         * be sent immediately after this event via the corresponding events in
         * zwlr_foreign_toplevel_handle_v1.
         */
        public fun toplevel(proxy: ZwlrForeignToplevelManagerV1, toplevel: ZwlrForeignToplevelHandleV1) {}

        /**
         * The compositor has finished with the toplevel manager
         *
         * This event indicates that the compositor is done sending events to the
         * zwlr_foreign_toplevel_manager_v1. The server will destroy the object
         * immediately after sending this request, so it will become invalid and
         * the client should free any resources associated with it.
         */
        public fun finished(proxy: ZwlrForeignToplevelManagerV1) {}
    }

    /**
     * Stop sending events
     *
     * Indicates the client no longer wishes to receive events for new toplevels.
     * However the compositor may emit further toplevel_created events, until
     * the finished event is emitted.
     *
     * The client must not send any more requests after this one.
     */
    public fun stop(): Unit {
        if (Wire.tracing) Wire.sent(this, "stop", "")
        request(0).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val toplevel = args.newId(ZwlrForeignToplevelHandleV1, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "toplevel", "toplevel=${Wire.show(toplevel)}")
                handler?.toplevel(this, toplevel)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "finished", "")
                handler?.finished(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrForeignToplevelManagerV1>("zwlr_foreign_toplevel_manager_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrForeignToplevelManagerV1 = ZwlrForeignToplevelManagerV1(connection, id, version)
    }
}
