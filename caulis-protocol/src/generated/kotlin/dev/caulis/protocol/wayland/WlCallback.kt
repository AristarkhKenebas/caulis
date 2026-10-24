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
 * Callback object
 *
 * Clients can handle the 'done' event to get notified when
 * the related request is done.
 *
 * Note, because wl_callback objects are created from multiple independent
 * factory interfaces, the wl_callback interface is frozen at version 1.
 */
public class WlCallback private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlCallback> get() = Companion

    /**
     * Done event
     *
     * Notify the client when the related request is done.
     */
    public fun sendDone(callbackData: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "callback_data=${Wire.show(callbackData)}")
        event(0).uint(callbackData).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_callback has no requests")
    }

    public companion object : ResourceType<WlCallback>("wl_callback", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlCallback = WlCallback(client, id, version)
    }
}
