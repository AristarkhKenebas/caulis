// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Core global object
 *
 * The core global object.  This is a special singleton object.  It
 * is used for internal Wayland protocol features.
 */
public class WlDisplay private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlDisplay> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun error(proxy: WlDisplay, objectId: Proxy?, code: Int, message: String) {}

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
        public fun deleteId(proxy: WlDisplay, id: Int) {}
    }

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
    public fun sync(): WlCallback {
        val created = connection.createProxy(WlCallback, this.version)
        if (Wire.tracing) Wire.sent(this, "sync", "callback=${Wire.show(created)}")
        request(0).newId(created).end()
        return created
    }

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
    public fun getRegistry(): WlRegistry {
        val created = connection.createProxy(WlRegistry, this.version)
        if (Wire.tracing) Wire.sent(this, "get_registry", "registry=${Wire.show(created)}")
        request(1).newId(created).end()
        return created
    }

    /** global error values */
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val objectId = args.objOrNull(Proxy::class.java)
                val code = args.uint()
                val message = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "error", "object_id=${Wire.show(objectId)}, code=${Wire.show(code)}, message=${Wire.show(message)}")
                handler?.error(this, objectId, code, message)
            }
            1 -> {
                val id = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "delete_id", "id=${Wire.show(id)}")
                handler?.deleteId(this, id)
            }
        }
    }

    public companion object : ProxyType<WlDisplay>("wl_display", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlDisplay = WlDisplay(connection, id, version)
    }
}
