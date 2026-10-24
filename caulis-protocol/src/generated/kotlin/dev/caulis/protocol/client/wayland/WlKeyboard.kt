// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Keyboard input device
 *
 * The wl_keyboard interface represents one or more keyboards
 * associated with a seat.
 *
 * Each wl_keyboard has the following logical state:
 *
 * - an active surface (possibly null),
 * - the keys currently logically down,
 * - the active modifiers,
 * - the active group.
 *
 * By default, the active surface is null, the keys currently logically down
 * are empty, the active modifiers and the active group are 0.
 */
public class WlKeyboard private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlKeyboard> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Keyboard mapping
         *
         * This event provides a file descriptor to the client which can be
         * memory-mapped in read-only mode to provide a keyboard mapping
         * description.
         *
         * From version 7 onwards, the fd must be mapped with MAP_PRIVATE by
         * the recipient, as MAP_SHARED may fail.
         */
        public fun keymap(proxy: WlKeyboard, format: Int, fd: Fd, size: Int) {}

        /**
         * Enter event
         *
         * Notification that this seat's keyboard focus is on a certain
         * surface.
         *
         * The compositor must send the wl_keyboard.modifiers event after this
         * event.
         *
         * In the wl_keyboard logical state, this event sets the active surface to
         * the surface argument and the keys currently logically down to the keys
         * in the keys argument. The compositor must not send this event if the
         * wl_keyboard already had an active surface immediately before this event.
         *
         * Clients should not use the list of pressed keys to emulate key-press
         * events. The order of keys in the list is unspecified.
         */
        public fun enter(proxy: WlKeyboard, serial: Int, surface: WlSurface, keys: ByteArray) {}

        /**
         * Leave event
         *
         * Notification that this seat's keyboard focus is no longer on
         * a certain surface.
         *
         * The leave notification is sent before the enter notification
         * for the new focus.
         *
         * In the wl_keyboard logical state, this event resets all values to their
         * defaults. The compositor must not send this event if the active surface
         * of the wl_keyboard was not equal to the surface argument immediately
         * before this event.
         */
        public fun leave(proxy: WlKeyboard, serial: Int, surface: WlSurface) {}

        /**
         * Key event
         *
         * A key was pressed or released.
         * The time argument is a timestamp with millisecond
         * granularity, with an undefined base.
         *
         * The key is a platform-specific key code that can be interpreted
         * by feeding it to the keyboard mapping (see the keymap event).
         *
         * If this event produces a change in modifiers, then the resulting
         * wl_keyboard.modifiers event must be sent after this event.
         *
         * In the wl_keyboard logical state, this event adds the key to the keys
         * currently logically down (if the state argument is pressed) or removes
         * the key from the keys currently logically down (if the state argument is
         * released). The compositor must not send this event if the wl_keyboard
         * did not have an active surface immediately before this event. The
         * compositor must not send this event if state is pressed (resp. released)
         * and the key was already logically down (resp. was not logically down)
         * immediately before this event.
         *
         * Since version 10, compositors may send key events with the "repeated"
         * key state when a wl_keyboard.repeat_info event with a rate argument of
         * 0 has been received. This allows the compositor to take over the
         * responsibility of key repetition.
         */
        public fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) {}

        /**
         * Modifier and group state
         *
         * Notifies clients that the modifier and/or group state has
         * changed, and it should update its local state.
         *
         * The compositor may send this event without a surface of the client
         * having keyboard focus, for example to tie modifier information to
         * pointer focus instead. If a modifier event with pressed modifiers is sent
         * without a prior enter event, the client can assume the modifier state is
         * valid until it receives the next wl_keyboard.modifiers event. In order to
         * reset the modifier state again, the compositor can send a
         * wl_keyboard.modifiers event with no pressed modifiers.
         *
         * In the wl_keyboard logical state, this event updates the modifiers and
         * group.
         */
        public fun modifiers(proxy: WlKeyboard, serial: Int, modsDepressed: Int, modsLatched: Int, modsLocked: Int, group: Int) {}

        /**
         * Repeat rate and delay
         *
         * Informs the client about the keyboard's repeat rate and delay.
         *
         * This event is sent as soon as the wl_keyboard object has been created,
         * and is guaranteed to be received by the client before any key press
         * event.
         *
         * Negative values for either rate or delay are illegal. A rate of zero
         * will disable any repeating (regardless of the value of delay).
         *
         * This event can be sent later on as well with a new value if necessary,
         * so clients should continue listening for the event past the creation
         * of wl_keyboard.
         *
         * Since version 4.
         */
        public fun repeatInfo(proxy: WlKeyboard, rate: Int, delay: Int) {}
    }

    /**
     * Release the keyboard object
     *
     * Since version 3.
     */
    public fun release(): Unit {
        check(version >= 3) { "wl_keyboard.release needs version 3, this object has $version" }
        request(0).end()
        connection.destroyProxy(this)
    }

    /** keyboard mapping format */
    public object KeymapFormat {
        /** no keymap; client must understand how to interpret the raw keycode */
        public const val NO_KEYMAP: Int = 0
        /** libxkbcommon compatible, null-terminated string; to determine the xkb keycode, clients must add 8 to the key event keycode */
        public const val XKB_V1: Int = 1
    }

    /** physical key state */
    public object KeyState {
        /** key is not pressed */
        public const val RELEASED: Int = 0
        /** key is pressed */
        public const val PRESSED: Int = 1
        /** key was repeated */
        public const val REPEATED: Int = 2
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val format = args.uint()
                val fd = args.fd()
                val size = args.uint()
                args.end()
                handler?.keymap(this, format, fd, size)
            }
            1 -> {
                val serial = args.uint()
                val surface = args.obj(WlSurface::class.java)
                val keys = args.array()
                args.end()
                handler?.enter(this, serial, surface, keys)
            }
            2 -> {
                val serial = args.uint()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                handler?.leave(this, serial, surface)
            }
            3 -> {
                val serial = args.uint()
                val time = args.uint()
                val key = args.uint()
                val state = args.uint()
                args.end()
                handler?.key(this, serial, time, key, state)
            }
            4 -> {
                val serial = args.uint()
                val modsDepressed = args.uint()
                val modsLatched = args.uint()
                val modsLocked = args.uint()
                val group = args.uint()
                args.end()
                handler?.modifiers(this, serial, modsDepressed, modsLatched, modsLocked, group)
            }
            5 -> {
                val rate = args.int()
                val delay = args.int()
                args.end()
                handler?.repeatInfo(this, rate, delay)
            }
        }
    }

    public companion object : ProxyType<WlKeyboard>("wl_keyboard", 11) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlKeyboard = WlKeyboard(connection, id, version)
    }
}
