// Generated from keyboard-shortcuts-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.keyboardshortcutsinhibitunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

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
public class ZwpKeyboardShortcutsInhibitorV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpKeyboardShortcutsInhibitorV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_keyboard_shortcuts_inhibitor_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the keyboard shortcuts inhibitor object
         *
         * Remove the keyboard shortcuts inhibitor from the associated wl_surface.
         */
        public fun destroy(resource: ZwpKeyboardShortcutsInhibitorV1)
    }

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
    public fun sendActive() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "active", "")
        event(0).end()
    }

    /**
     * Shortcuts are restored
     *
     * This event indicates that the shortcuts inhibitor is inactive,
     * normal shortcuts processing is restored by the compositor.
     */
    public fun sendInactive() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "inactive", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_keyboard_shortcuts_inhibitor_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_keyboard_shortcuts_inhibitor_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpKeyboardShortcutsInhibitorV1>("zwp_keyboard_shortcuts_inhibitor_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpKeyboardShortcutsInhibitorV1 = ZwpKeyboardShortcutsInhibitorV1(client, id, version)
    }
}
