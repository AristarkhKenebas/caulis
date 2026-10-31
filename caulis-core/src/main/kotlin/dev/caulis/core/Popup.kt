package dev.caulis.core

import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.xdgshell.XdgPopup
import dev.caulis.protocol.xdgshell.XdgPositioner

/** A menu or tooltip attached to a window or another popup (xdg_popup). */
class Popup internal constructor(
    val resource: XdgPopup,
    val shell: ShellSurface,
    /** The window or menu it belongs to; null for a panel's menu (see [layerParent]). */
    val parent: ShellSurface?,
    private var rules: Positioner.Rules,
) {
    /** The panel (layer surface) it belongs to, if a panel opened it. */
    var layerParent: LayerSurface? = null
        internal set

    val surface: Surface get() = shell.surface

    /** Position of the popup's visible area relative to the parent's visible area. */
    var x = 0
        private set
    var y = 0
        private set

    init {
        resource.data = this
        resource.requests = object : XdgPopup.Requests {
            override fun destroy(resource: XdgPopup) = shell.roleDestroyed()

            override fun grab(resource: XdgPopup, seat: WlSeat, serial: Int) {
                if (shell.mapped) {
                    resource.postError(XdgPopup.Error.INVALID_GRAB, "a grab after the popup was mapped")
                    return
                }
                shell.shell.listener.popupGrab(this@Popup, serial)
            }

            override fun reposition(resource: XdgPopup, positioner: XdgPositioner, token: Int) {
                rules = positioner.dataAs<Positioner>()!!.copy()
                resource.sendRepositioned(token)
                configure()
            }
        }
    }

    internal fun configure() {
        val box = rules.place(shell.shell.listener.popupBounds(this))
        x = box.x
        y = box.y
        resource.sendConfigure(box.x, box.y, box.width, box.height)
        shell.sendConfigure()
    }

    /** Closes the popup (e.g. the user clicked elsewhere). */
    fun dismiss() = resource.sendPopupDone()
}
