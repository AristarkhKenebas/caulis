// Generated from keyboard-shortcuts-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.keyboardshortcutsinhibitunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Context object for keyboard grab_manager
 *
 * A global interface used for inhibiting the compositor keyboard shortcuts.
 */
public class ZwpKeyboardShortcutsInhibitManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpKeyboardShortcutsInhibitManagerV1> get() = Companion

    /**
     * Destroy the keyboard shortcuts inhibitor object
     *
     * Destroy the keyboard shortcuts inhibitor manager.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a new keyboard shortcuts inhibitor object
     *
     * Create a new keyboard shortcuts inhibitor object associated with
     * the given surface for the given seat.
     *
     * If shortcuts are already inhibited for the specified seat and surface,
     * a protocol error "already_inhibited" is raised by the compositor.
     */
    public fun inhibitShortcuts(surface: WlSurface, seat: WlSeat): ZwpKeyboardShortcutsInhibitorV1 {
        val created = connection.createProxy(ZwpKeyboardShortcutsInhibitorV1, this.version)
        if (Wire.tracing) Wire.sent(this, "inhibit_shortcuts", "id=${Wire.show(created)}, surface=${Wire.show(surface)}, seat=${Wire.show(seat)}")
        request(1).newId(created).obj(surface).obj(seat).end()
        return created
    }

    public object Error {
        /** the shortcuts are already inhibited for this surface */
        public const val ALREADY_INHIBITED: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwpKeyboardShortcutsInhibitManagerV1>("zwp_keyboard_shortcuts_inhibit_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpKeyboardShortcutsInhibitManagerV1 = ZwpKeyboardShortcutsInhibitManagerV1(connection, id, version)
    }
}
