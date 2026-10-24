// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Core global object
 *
 * The core global object.  This is a special singleton object.  It
 * is used for internal Wayland protocol features.
 */
public class WlDisplay private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlDisplay> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_display. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Asynchronous roundtrip
         *
         * The sync request asks the server to emit the 'done' event
         * on the returned wl_callback object.  Since requests are
         * handled in-order and events are delivered in-order, this can
         * be used as a barrier to ensure all previous requests and the
         * resulting events have been handled.
         *
         * The object returned by this request will be destroyed by the
         * compositor after the callback is fired and as such the client must not
         * attempt to use it after that point.
         *
         * The callback_data passed in the callback is undefined and should be ignored.
         */
        public fun sync(resource: WlDisplay, callback: WlCallback)

        /**
         * Get global registry object
         *
         * This request creates a registry object that allows the client
         * to list and bind the global objects available from the
         * compositor.
         *
         * It should be noted that the server side resources consumed in
         * response to a get_registry request can only be released when the
         * client disconnects, not when the client side proxy is destroyed.
         * Therefore, clients should invoke get_registry as infrequently as
         * possible to avoid wasting memory.
         */
        public fun getRegistry(resource: WlDisplay, registry: WlRegistry)
    }

    /**
     * Fatal error event
     *
     * The error event is sent out when a fatal (non-recoverable)
     * error has occurred.  The object_id argument is the object
     * where the error occurred, most often in response to a request
     * to that object.  The code identifies the error and is defined
     * by the object interface.  As such, each interface defines its
     * own set of error codes.  The message is a brief description
     * of the error, for (debugging) convenience.
     */
    public fun sendError(objectId: Resource, code: Int, message: String) {
        if (!isAlive || !client.isConnected) return
        event(0).obj(objectId).uint(code).string(message).end()
    }

    /**
     * Acknowledge object ID deletion
     *
     * This event is used internally by the object ID management logic.
     *
     * When the server stops using an object created by the client, the server
     * sends this event. In particular, after sending this event, the server
     * will no longer send any events that contain the object as the receiver
     * or as an argument.
     *
     * When the client receives this event, it knows that it can reuse the
     * object ID.
     */
    public fun sendDeleteId(id: Int) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(id).end()
    }

    /**
     * Global error values
     */
    public object Error {
        /** server couldn't find object */
        public const val INVALID_OBJECT: Int = 0
        /** method doesn't exist on the specified interface or malformed request */
        public const val INVALID_METHOD: Int = 1
        /** server is out of memory */
        public const val NO_MEMORY: Int = 2
        /** implementation error in compositor */
        public const val IMPLEMENTATION: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_display is not implemented")
        when (opcode) {
            0 -> {
                val callbackId = args.newId()
                args.end()
                val callback = client.register(WlCallback.create(client, callbackId, version))
                handler.sync(this, callback)
            }
            1 -> {
                val registryId = args.newId()
                args.end()
                val registry = client.register(WlRegistry.create(client, registryId, version))
                handler.getRegistry(this, registry)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_display request $opcode")
        }
    }

    public companion object : ResourceType<WlDisplay>("wl_display", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlDisplay = WlDisplay(client, id, version)
    }
}
