// Generated from xdg-dialog-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgdialogv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.xdgshell.XdgToplevel

/**
 * Create dialogs related to other toplevels
 *
 * The xdg_wm_dialog_v1 interface is exposed as a global object allowing
 * to register surfaces with a xdg_toplevel role as "dialogs" relative to
 * another toplevel.
 *
 * The compositor may let this relation influence how the surface is
 * placed, displayed or interacted with.
 *
 * Warning! The protocol described in this file is currently in the testing
 * phase. Backward compatible changes may be added together with the
 * corresponding interface version bump. Backward incompatible changes can
 * only be done by creating a new major version of the extension.
 */
public class XdgWmDialogV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgWmDialogV1> get() = Companion

    /**
     * Destroy the dialog manager object
     *
     * Destroys the xdg_wm_dialog_v1 object. This does not affect
     * the xdg_dialog_v1 objects generated through it.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Create a dialog object
     *
     * Creates a xdg_dialog_v1 object for the given toplevel. See the interface
     *        description for more details.
     *
     * Compositors must raise an already_used error if clients attempt to
     * create multiple xdg_dialog_v1 objects for the same xdg_toplevel.
     */
    public fun getXdgDialog(toplevel: XdgToplevel): XdgDialogV1 {
        val created = connection.createProxy(XdgDialogV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_xdg_dialog", "id=${Wire.show(created)}, toplevel=${Wire.show(toplevel)}")
        request(1).newId(created).obj(toplevel).end()
        return created
    }

    public object Error {
        /** the xdg_toplevel object has already been used to create a xdg_dialog_v1 */
        public const val ALREADY_USED: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XdgWmDialogV1>("xdg_wm_dialog_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgWmDialogV1 = XdgWmDialogV1(connection, id, version)
    }
}
