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
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface

/**
 * Context object for keyboard grab_manager
 *
 * A global interface used for inhibiting the compositor keyboard shortcuts.
 */
public class ZwpKeyboardShortcutsInhibitManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpKeyboardShortcutsInhibitManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_keyboard_shortcuts_inhibit_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the keyboard shortcuts inhibitor object
         *
         * Destroy the keyboard shortcuts inhibitor manager.
         */
        public fun destroy(resource: ZwpKeyboardShortcutsInhibitManagerV1)

        /**
         * Create a new keyboard shortcuts inhibitor object
         *
         * Create a new keyboard shortcuts inhibitor object associated with
         * the given surface for the given seat.
         *
         * If shortcuts are already inhibited for the specified seat and surface,
         * a protocol error "already_inhibited" is raised by the compositor.
         */
        public fun inhibitShortcuts(resource: ZwpKeyboardShortcutsInhibitManagerV1, id: ZwpKeyboardShortcutsInhibitorV1, surface: WlSurface, seat: WlSeat)
    }

    public object Error {
        /** the shortcuts are already inhibited for this surface */
        public const val ALREADY_INHIBITED: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_keyboard_shortcuts_inhibit_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(ZwpKeyboardShortcutsInhibitorV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "inhibit_shortcuts", "id=${Wire.show(id)}, surface=${Wire.show(surface)}, seat=${Wire.show(seat)}")
                handler.inhibitShortcuts(this, id, surface, seat)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_keyboard_shortcuts_inhibit_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpKeyboardShortcutsInhibitManagerV1>("zwp_keyboard_shortcuts_inhibit_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpKeyboardShortcutsInhibitManagerV1 = ZwpKeyboardShortcutsInhibitManagerV1(client, id, version)
    }
}
