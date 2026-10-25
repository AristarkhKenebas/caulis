// Generated from keyboard-shortcuts-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.keyboardshortcutsinhibitunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Context object for keyboard shortcuts inhibitor
 *
 * A keyboard shortcuts inhibitor instructs the compositor to ignore
 * its own keyboard shortcuts when the associated surface has keyboard
 * focus. As a result, when the surface has keyboard focus on the given
 * seat, it will receive all key events originating from the specified
 * seat, even those which would normally be caught by the compositor for
 * its own shortcuts.
 *
 * The Wayland compositor is however under no obligation to disable
 * all of its shortcuts, and may keep some special key combo for its own
 * use, including but not limited to one allowing the user to forcibly
 * restore normal keyboard events routing in the case of an unwilling
 * client. The compositor may also use the same key combo to reactivate
 * an existing shortcut inhibitor that was previously deactivated on
 * user request.
 *
 * When the compositor restores its own keyboard shortcuts, an
 * "inactive" event is emitted to notify the client that the keyboard
 * shortcuts inhibitor is not effectively active for the surface and
 * seat any more, and the client should not expect to receive all
 * keyboard events.
 *
 * When the keyboard shortcuts inhibitor is inactive, the client has
 * no way to forcibly reactivate the keyboard shortcuts inhibitor.
 *
 * The user can chose to re-enable a previously deactivated keyboard
 * shortcuts inhibitor using any mechanism the compositor may offer,
 * in which case the compositor will send an "active" event to notify
 * the client.
 *
 * If the surface is destroyed, unmapped, or loses the seat's keyboard
 * focus, the keyboard shortcuts inhibitor becomes irrelevant and the
 * compositor will restore its own keyboard shortcuts but no "inactive"
 * event is emitted in this case.
 */
public class ZwpKeyboardShortcutsInhibitorV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpKeyboardShortcutsInhibitorV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Shortcuts are inhibited
         *
         * This event indicates that the shortcut inhibitor is active.
         *
         * The compositor sends this event every time compositor shortcuts
         * are inhibited on behalf of the surface. When active, the client
         * may receive input events normally reserved by the compositor
         * (see zwp_keyboard_shortcuts_inhibitor_v1).
         *
         * This occurs typically when the initial request "inhibit_shortcuts"
         * first becomes active or when the user instructs the compositor to
         * re-enable and existing shortcuts inhibitor using any mechanism
         * offered by the compositor.
         */
        public fun active(proxy: ZwpKeyboardShortcutsInhibitorV1) {}

        /**
         * Shortcuts are restored
         *
         * This event indicates that the shortcuts inhibitor is inactive,
         * normal shortcuts processing is restored by the compositor.
         */
        public fun inactive(proxy: ZwpKeyboardShortcutsInhibitorV1) {}
    }

    /**
     * Destroy the keyboard shortcuts inhibitor object
     *
     * Remove the keyboard shortcuts inhibitor from the associated wl_surface.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "active", "")
                handler?.active(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "inactive", "")
                handler?.inactive(this)
            }
        }
    }

    public companion object : ProxyType<ZwpKeyboardShortcutsInhibitorV1>("zwp_keyboard_shortcuts_inhibitor_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpKeyboardShortcutsInhibitorV1 = ZwpKeyboardShortcutsInhibitorV1(connection, id, version)
    }
}
