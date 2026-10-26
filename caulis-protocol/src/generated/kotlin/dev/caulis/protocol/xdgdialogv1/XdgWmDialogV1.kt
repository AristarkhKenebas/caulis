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
import dev.caulis.protocol.xdgshell.XdgToplevel

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
public class XdgWmDialogV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgWmDialogV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_wm_dialog_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the dialog manager object
         *
         * Destroys the xdg_wm_dialog_v1 object. This does not affect
         * the xdg_dialog_v1 objects generated through it.
         */
        public fun destroy(resource: XdgWmDialogV1)

        /**
         * Create a dialog object
         *
         * Creates a xdg_dialog_v1 object for the given toplevel. See the interface
         *        description for more details.
         *
         * Compositors must raise an already_used error if clients attempt to
         * create multiple xdg_dialog_v1 objects for the same xdg_toplevel.
         */
        public fun getXdgDialog(resource: XdgWmDialogV1, id: XdgDialogV1, toplevel: XdgToplevel)
    }

    public object Error {
        /** the xdg_toplevel object has already been used to create a xdg_dialog_v1 */
        public const val ALREADY_USED: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_wm_dialog_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val toplevel = args.obj(XdgToplevel::class.java)
                args.end()
                val id = client.register(XdgDialogV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_xdg_dialog", "id=${Wire.show(id)}, toplevel=${Wire.show(toplevel)}")
                handler.getXdgDialog(this, id, toplevel)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_wm_dialog_v1 request $opcode")
        }
    }

    public companion object : ResourceType<XdgWmDialogV1>("xdg_wm_dialog_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): XdgWmDialogV1 = XdgWmDialogV1(client, id, version)
    }
}
