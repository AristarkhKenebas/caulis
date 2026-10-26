// Generated from xdg-dialog-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgdialogv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Dialog object
 *
 * A xdg_dialog_v1 object is an ancillary object tied to a xdg_toplevel. Its
 * purpose is hinting the compositor that the toplevel is a "dialog" (e.g. a
 * temporary window) relative to another toplevel (see
 * xdg_toplevel.set_parent). If the xdg_toplevel is destroyed, the xdg_dialog_v1
 * becomes inert.
 *
 * Through this object, the client may provide additional hints about
 * the purpose of the secondary toplevel. This interface has no effect
 * on toplevels that are not attached to a parent toplevel.
 */
public class XdgDialogV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgDialogV1> get() = Companion

    /**
     * Destroy the dialog object
     *
     * Destroys the xdg_dialog_v1 object. If this object is destroyed
     * before the related xdg_toplevel, the compositor should unapply its
     * effects.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Mark dialog as modal
     *
     * Hints that the dialog has "modal" behavior. Modal dialogs typically
     * require to be fully addressed by the user (i.e. closed) before resuming
     * interaction with the parent toplevel, and may require a distinct
     * presentation.
     *
     * Clients must implement the logic to filter events in the parent
     * toplevel on their own.
     *
     * Compositors may choose any policy in event delivery to the parent
     * toplevel, from delivering all events unfiltered to using them for
     * internal consumption.
     */
    public fun setModal(): Unit {
        if (Wire.tracing) Wire.sent(this, "set_modal", "")
        request(1).end()
    }

    /**
     * Mark dialog as not modal
     *
     * Drops the hint that this dialog has "modal" behavior. See
     * xdg_dialog_v1.set_modal for more details.
     */
    public fun unsetModal(): Unit {
        if (Wire.tracing) Wire.sent(this, "unset_modal", "")
        request(2).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XdgDialogV1>("xdg_dialog_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgDialogV1 = XdgDialogV1(connection, id, version)
    }
}
