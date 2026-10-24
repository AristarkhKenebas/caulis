// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire

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
public class WlKeyboard private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlKeyboard> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_keyboard. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Release the keyboard object
         *
         * Since version 3.
         */
        public fun release(resource: WlKeyboard)
    }

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
    public fun sendKeymap(format: Int, fd: Fd, size: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "keymap", "format=${Wire.show(format)}, fd=${Wire.show(fd)}, size=${Wire.show(size)}")
        event(0).uint(format).fd(fd).uint(size).end()
    }

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
    public fun sendEnter(serial: Int, surface: WlSurface, keys: ByteArray) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "enter", "serial=${Wire.show(serial)}, surface=${Wire.show(surface)}, keys=${Wire.show(keys)}")
        event(1).uint(serial).obj(surface).array(keys).end()
    }

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
    public fun sendLeave(serial: Int, surface: WlSurface) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "leave", "serial=${Wire.show(serial)}, surface=${Wire.show(surface)}")
        event(2).uint(serial).obj(surface).end()
    }

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
    public fun sendKey(serial: Int, time: Int, key: Int, state: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "key", "serial=${Wire.show(serial)}, time=${Wire.show(time)}, key=${Wire.show(key)}, state=${Wire.show(state)}")
        event(3).uint(serial).uint(time).uint(key).uint(state).end()
    }

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
    public fun sendModifiers(serial: Int, modsDepressed: Int, modsLatched: Int, modsLocked: Int, group: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "modifiers", "serial=${Wire.show(serial)}, mods_depressed=${Wire.show(modsDepressed)}, mods_latched=${Wire.show(modsLatched)}, mods_locked=${Wire.show(modsLocked)}, group=${Wire.show(group)}")
        event(4).uint(serial).uint(modsDepressed).uint(modsLatched).uint(modsLocked).uint(group).end()
    }

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
    public fun sendRepeatInfo(rate: Int, delay: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 4) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "repeat_info", "rate=${Wire.show(rate)}, delay=${Wire.show(delay)}")
        event(5).int(rate).int(delay).end()
    }

    /**
     * Keyboard mapping format
     */
    public object KeymapFormat {
        /** no keymap; client must understand how to interpret the raw keycode */
        public const val NO_KEYMAP: Int = 0
        /** libxkbcommon compatible, null-terminated string; to determine the xkb keycode, clients must add 8 to the key event keycode */
        public const val XKB_V1: Int = 1
    }

    /**
     * Physical key state
     */
    public object KeyState {
        /** key is not pressed */
        public const val RELEASED: Int = 0
        /** key is pressed */
        public const val PRESSED: Int = 1
        /** key was repeated */
        public const val REPEATED: Int = 2
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_keyboard is not implemented")
        when (opcode) {
            0 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_keyboard.release needs version 3")
                args.end()
                if (Wire.tracing) Wire.request(this, "release", "")
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_keyboard request $opcode")
        }
    }

    public companion object : ResourceType<WlKeyboard>("wl_keyboard", 11) {
        internal override fun create(client: Client, id: Int, version: Int): WlKeyboard = WlKeyboard(client, id, version)
    }
}
