// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Group of input devices
 *
 * A seat is a group of keyboards, pointer and touch devices. This
 * object is published as a global during start up, or when such a
 * device is hot plugged.  A seat typically has a pointer and
 * maintains a keyboard focus and a pointer focus.
 */
public class WlSeat private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlSeat> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_seat. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Return pointer object
         *
         * The ID provided will be initialized to the wl_pointer interface
         * for this seat.
         *
         * This request only takes effect if the seat has the pointer
         * capability, or has had the pointer capability in the past.
         * It is a protocol violation to issue this request on a seat that has
         * never had the pointer capability. The missing_capability error will
         * be sent in this case.
         */
        public fun getPointer(resource: WlSeat, id: WlPointer)

        /**
         * Return keyboard object
         *
         * The ID provided will be initialized to the wl_keyboard interface
         * for this seat.
         *
         * This request only takes effect if the seat has the keyboard
         * capability, or has had the keyboard capability in the past.
         * It is a protocol violation to issue this request on a seat that has
         * never had the keyboard capability. The missing_capability error will
         * be sent in this case.
         */
        public fun getKeyboard(resource: WlSeat, id: WlKeyboard)

        /**
         * Return touch object
         *
         * The ID provided will be initialized to the wl_touch interface
         * for this seat.
         *
         * This request only takes effect if the seat has the touch
         * capability, or has had the touch capability in the past.
         * It is a protocol violation to issue this request on a seat that has
         * never had the touch capability. The missing_capability error will
         * be sent in this case.
         */
        public fun getTouch(resource: WlSeat, id: WlTouch)

        /**
         * Release the seat object
         *
         * Using this request a client can tell the server that it is not going to
         * use the seat object anymore.
         *
         * Since version 5.
         */
        public fun release(resource: WlSeat)
    }

    /**
     * Seat capabilities changed
     *
     * This is sent on binding to the seat global or whenever a seat gains
     * or loses the pointer, keyboard or touch capabilities.
     * The argument is a capability enum containing the complete set of
     * capabilities this seat has.
     *
     * When the pointer capability is added, a client may create a
     * wl_pointer object using the wl_seat.get_pointer request. This object
     * will receive pointer events until the capability is removed in the
     * future.
     *
     * When the pointer capability is removed, a client should destroy the
     * wl_pointer objects associated with the seat where the capability was
     * removed, using the wl_pointer.release request. No further pointer
     * events will be received on these objects.
     *
     * In some compositors, if a seat regains the pointer capability and a
     * client has a previously obtained wl_pointer object of version 4 or
     * less, that object may start sending pointer events again. This
     * behavior is considered a misinterpretation of the intended behavior
     * and must not be relied upon by the client. wl_pointer objects of
     * version 5 or later must not send events if created before the most
     * recent event notifying the client of an added pointer capability.
     *
     * The above behavior also applies to wl_keyboard and wl_touch with the
     * keyboard and touch capabilities, respectively.
     */
    public fun sendCapabilities(capabilities: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(capabilities).end()
    }

    /**
     * Unique identifier for this seat
     *
     * In a multi-seat configuration the seat name can be used by clients to
     * help identify which physical devices the seat represents.
     *
     * The seat name is a UTF-8 string with no convention defined for its
     * contents. Each name is unique among all wl_seat globals. The name is
     * only guaranteed to be unique for the current compositor instance.
     *
     * The same seat names are used for all clients. Thus, the name can be
     * shared across processes to refer to a specific wl_seat global.
     *
     * The name event is sent after binding to the seat global, and should be sent
     * before announcing capabilities. This event is only sent once per seat object,
     * and the name does not change over the lifetime of the wl_seat global.
     *
     * Compositors may re-use the same seat name if the wl_seat global is
     * destroyed and re-created later.
     *
     * Since version 2.
     */
    public fun sendName(name: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        event(1).string(name).end()
    }

    /**
     * Seat capability bitmask
     *
     * Bit flags: combine with `or`.
     */
    public object Capability {
        /** the seat has pointer devices */
        public const val POINTER: Int = 1
        /** the seat has one or more keyboards */
        public const val KEYBOARD: Int = 2
        /** the seat has touch devices */
        public const val TOUCH: Int = 4
    }

    /**
     * Wl_seat error values
     */
    public object Error {
        /** get_pointer, get_keyboard or get_touch called on seat without the matching capability */
        public const val MISSING_CAPABILITY: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_seat is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlPointer.create(client, idId, version))
                handler.getPointer(this, id)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlKeyboard.create(client, idId, version))
                handler.getKeyboard(this, id)
            }
            2 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(WlTouch.create(client, idId, version))
                handler.getTouch(this, id)
            }
            3 -> {
                if (version < 5) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_seat.release needs version 5")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_seat request $opcode")
        }
    }

    public companion object : ResourceType<WlSeat>("wl_seat", 11) {
        internal override fun create(client: Client, id: Int, version: Int): WlSeat = WlSeat(client, id, version)
    }
}
