// Generated from xdg-dialog-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgdialogv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

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
public class XdgDialogV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgDialogV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_dialog_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the dialog object
         *
         * Destroys the xdg_dialog_v1 object. If this object is destroyed
         * before the related xdg_toplevel, the compositor should unapply its
         * effects.
         */
        public fun destroy(resource: XdgDialogV1)

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
        public fun setModal(resource: XdgDialogV1)

        /**
         * Mark dialog as not modal
         *
         * Drops the hint that this dialog has "modal" behavior. See
         * xdg_dialog_v1.set_modal for more details.
         */
        public fun unsetModal(resource: XdgDialogV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_dialog_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "set_modal", "")
                handler.setModal(this)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "unset_modal", "")
                handler.unsetModal(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_dialog_v1 request $opcode")
        }
    }

    public companion object : ResourceType<XdgDialogV1>("xdg_dialog_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): XdgDialogV1 = XdgDialogV1(client, id, version)
    }
}
