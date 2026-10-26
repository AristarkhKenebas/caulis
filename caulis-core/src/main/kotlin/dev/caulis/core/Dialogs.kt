package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.xdgdialogv1.XdgDialogV1
import dev.caulis.protocol.xdgdialogv1.XdgWmDialogV1
import dev.caulis.protocol.xdgshell.XdgToplevel

/**
 * xdg-dialog-v1: programs say which of their dialogs are modal (their window waits for
 * them): [Window.modal]. Focusing the window then focuses the dialog.
 */
class Dialogs(display: Display, private val desktop: Desktop) {
    private val log = dev.caulis.log.Logger("windows")

    init {
        display.addGlobal(XdgWmDialogV1, 1) { manager ->
            manager.requests = object : XdgWmDialogV1.Requests {
                override fun getXdgDialog(resource: XdgWmDialogV1, id: XdgDialogV1, toplevel: XdgToplevel) {
                    val window = toplevel.dataAs<Window>() ?: return
                    id.onDestroy { set(window, false) }
                    id.requests = object : XdgDialogV1.Requests {
                        override fun setModal(resource: XdgDialogV1) = set(window, true)
                        override fun unsetModal(resource: XdgDialogV1) = set(window, false)
                        override fun destroy(resource: XdgDialogV1) {}
                    }
                }

                override fun destroy(resource: XdgWmDialogV1) {}
            }
        }
    }

    private fun set(window: Window, modal: Boolean) {
        if (window.modal == modal) return
        window.modalAsked(modal)
        log.info { "$window is ${if (modal) "" else "no longer "}modal" }
        // The window it belongs to had the focus: the dialog takes it.
        if (modal && window.parent != null && desktop.focusedWindow === window.parent) desktop.focus(window)
    }
}
