// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Callback object
 *
 * Clients can handle the 'done' event to get notified when
 * the related request is done.
 *
 * Note, because wl_callback objects are created from multiple independent
 * factory interfaces, the wl_callback interface is frozen at version 1.
 */
public class WlCallback private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlCallback> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Done event
         *
         * Notify the client when the related request is done.
         */
        public fun done(proxy: WlCallback, callbackData: Int) {}
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val callbackData = args.uint()
                args.end()
                handler?.done(this, callbackData)
            }
        }
    }

    public companion object : ProxyType<WlCallback>("wl_callback", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlCallback = WlCallback(connection, id, version)
    }
}
