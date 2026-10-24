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
 * List toplevels
 *
 * A toplevel is defined as a surface with a role similar to xdg_toplevel.
 * XWayland surfaces may be treated like toplevels in this protocol.
 *
 * After a client binds the ext_foreign_toplevel_list_v1, each mapped
 * toplevel window will be sent using the ext_foreign_toplevel_list_v1.toplevel
 * event.
 *
 * Clients which only care about the current state can perform a roundtrip after
 * binding this global.
 *
 * For each instance of ext_foreign_toplevel_list_v1, the compositor must
 * create a new ext_foreign_toplevel_handle_v1 object for each mapped toplevel.
 *
 * If a compositor implementation sends the ext_foreign_toplevel_list_v1.finished
 * event after the global is bound, the compositor must not send any
 * ext_foreign_toplevel_list_v1.toplevel events.
 */
public class ExtForeignToplevelListV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtForeignToplevelListV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_foreign_toplevel_list_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Stop sending events
         *
         * This request indicates that the client no longer wishes to receive
         * events for new toplevels.
         *
         * The Wayland protocol is asynchronous, meaning the compositor may send
         * further toplevel events until the stop request is processed.
         * The client should wait for a ext_foreign_toplevel_list_v1.finished
         * event before destroying this object.
         */
        public fun stop(resource: ExtForeignToplevelListV1)

        /**
         * Destroy the ext_foreign_toplevel_list_v1 object
         *
         * This request should be called either when the client will no longer
         * use the ext_foreign_toplevel_list_v1 or after the finished event
         * has been received to allow destruction of the object.
         *
         * If a client wishes to destroy this object it should send a
         * ext_foreign_toplevel_list_v1.stop request and wait for a ext_foreign_toplevel_list_v1.finished
         * event, then destroy the handles and then this object.
         */
        public fun destroy(resource: ExtForeignToplevelListV1)
    }

    /**
     * A toplevel has been created
     *
     * This event is emitted whenever a new toplevel window is created. It is
     * emitted for all toplevels, regardless of the app that has created them.
     *
     * All initial properties of the toplevel (identifier, title, app_id) will be sent
     * immediately after this event using the corresponding events for
     * ext_foreign_toplevel_handle_v1. The compositor will use the
     * ext_foreign_toplevel_handle_v1.done event to indicate when all data has
     * been sent.
     */
    public fun sendToplevel(toplevel: ExtForeignToplevelHandleV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "toplevel", "toplevel=${Wire.show(toplevel)}")
        event(0).newId(toplevel).end()
    }

    /**
     * The compositor has finished with the toplevel manager
     *
     * This event indicates that the compositor is done sending events
     * to this object. The client should destroy the object.
     * See ext_foreign_toplevel_list_v1.destroy for more information.
     *
     * The compositor must not send any more toplevel events after this event.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_foreign_toplevel_list_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "stop", "")
                handler.stop(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_foreign_toplevel_list_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtForeignToplevelListV1>("ext_foreign_toplevel_list_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtForeignToplevelListV1 = ExtForeignToplevelListV1(client, id, version)
    }
}
