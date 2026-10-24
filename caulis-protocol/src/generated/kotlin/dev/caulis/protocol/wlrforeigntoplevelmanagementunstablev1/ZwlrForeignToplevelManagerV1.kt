// Generated from wlr-foreign-toplevel-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrforeigntoplevelmanagementunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

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
public class ZwlrForeignToplevelManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrForeignToplevelManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_foreign_toplevel_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Stop sending events
         *
         * Indicates the client no longer wishes to receive events for new toplevels.
         * However the compositor may emit further toplevel_created events, until
         * the finished event is emitted.
         *
         * The client must not send any more requests after this one.
         */
        public fun stop(resource: ZwlrForeignToplevelManagerV1)
    }

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
    public fun sendToplevel(toplevel: ZwlrForeignToplevelHandleV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "toplevel", "toplevel=${Wire.show(toplevel)}")
        event(0).newId(toplevel).end()
    }

    /**
     * The compositor has finished with the toplevel manager
     *
     * This event indicates that the compositor is done sending events to the
     * zwlr_foreign_toplevel_manager_v1. The server will destroy the object
     * immediately after sending this request, so it will become invalid and
     * the client should free any resources associated with it.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_foreign_toplevel_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "stop", "")
                handler.stop(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_foreign_toplevel_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrForeignToplevelManagerV1>("zwlr_foreign_toplevel_manager_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrForeignToplevelManagerV1 = ZwlrForeignToplevelManagerV1(client, id, version)
    }
}
