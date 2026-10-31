package dev.caulis.core

import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.xdgshell.XdgToplevel

/** A Wayland program's window (xdg_toplevel). */
class XdgWindow internal constructor(val resource: XdgToplevel, val shell: ShellSurface) : Window() {

    override val surface: Surface get() = shell.surface
    override val pid: Int get() = resource.client.pid
    override val visibleRect get() = shell.visibleRect
    override val configured: Boolean get() = shell.configured
    override val mapped: Boolean get() = shell.mapped

    private var capabilitiesSent = false

    private var configuredWidth = 0
    private var configuredHeight = 0

    init {
        resource.data = this
        resource.requests = object : XdgToplevel.Requests {
            override fun destroy(resource: XdgToplevel) = shell.roleDestroyed()

            override fun setParent(resource: XdgToplevel, parent: XdgToplevel?) {
                val wanted = parent?.dataAs<Window>()
                // Not itself, nor one of its own children (a loop of parents).
                var ancestor = wanted
                while (ancestor != null) {
                    if (ancestor === this@XdgWindow) {
                        resource.postError(XdgToplevel.Error.INVALID_PARENT, "a window cannot be its own parent")
                        return
                    }
                    ancestor = ancestor.parent
                }
                this@XdgWindow.parent = wanted
            }

            override fun setTitle(resource: XdgToplevel, title: String) {
                // (Passed on to panels and docks: cut to what any program can take.)
                val capped = resource.client.server.capForwarded(title)
                if (capped == this@XdgWindow.title) return
                this@XdgWindow.title = capped
                shell.shell.listener.windowInfoChanged(this@XdgWindow)
            }

            override fun setAppId(resource: XdgToplevel, appId: String) {
                val capped = resource.client.server.capForwarded(appId)
                if (capped == this@XdgWindow.appId) return
                this@XdgWindow.appId = capped
                shell.shell.listener.windowInfoChanged(this@XdgWindow)
            }

            override fun showWindowMenu(resource: XdgToplevel, seat: WlSeat, serial: Int, x: Int, y: Int) {}

            override fun move(resource: XdgToplevel, seat: WlSeat, serial: Int) {
                if (shell.mapped) shell.shell.listener.moveRequested(this@XdgWindow, serial)
            }

            override fun resize(resource: XdgToplevel, seat: WlSeat, serial: Int, edges: Int) {
                if (edges !in VALID_EDGES) {
                    resource.postError(XdgToplevel.Error.INVALID_RESIZE_EDGE, "invalid resize edge $edges")
                    return
                }
                if (shell.mapped) shell.shell.listener.resizeRequested(this@XdgWindow, serial, edges)
            }

            override fun setMaxSize(resource: XdgToplevel, width: Int, height: Int) {
                if (width < 0 || height < 0) {
                    resource.postError(XdgToplevel.Error.INVALID_SIZE, "negative size")
                    return
                }
                maxWidth = width
                maxHeight = height
            }

            override fun setMinSize(resource: XdgToplevel, width: Int, height: Int) {
                if (width < 0 || height < 0) {
                    resource.postError(XdgToplevel.Error.INVALID_SIZE, "negative size")
                    return
                }
                minWidth = width
                minHeight = height
            }

            override fun setMaximized(resource: XdgToplevel) = shell.shell.listener.maximizeRequested(this@XdgWindow, true)

            override fun unsetMaximized(resource: XdgToplevel) = shell.shell.listener.maximizeRequested(this@XdgWindow, false)

            override fun setFullscreen(resource: XdgToplevel, output: WlOutput?) =
                shell.shell.listener.fullscreenRequested(this@XdgWindow, true)

            override fun unsetFullscreen(resource: XdgToplevel) = shell.shell.listener.fullscreenRequested(this@XdgWindow, false)

            override fun setMinimized(resource: XdgToplevel) = shell.shell.listener.minimizeRequested(this@XdgWindow)
        }
    }

    override fun configure(width: Int, height: Int) {
        if (width != configuredWidth || height != configuredHeight) resizeAskedAt = System.nanoTime()
        configuredWidth = width
        configuredHeight = height
        // Before the first configure: which window features we support, and the screen size.
        if (!capabilitiesSent) {
            capabilitiesSent = true
            if (resource.version >= 5) {
                resource.sendWmCapabilities(uintArray(listOf(
                    XdgToplevel.WmCapabilities.MAXIMIZE,
                    XdgToplevel.WmCapabilities.FULLSCREEN,
                    XdgToplevel.WmCapabilities.MINIMIZE,
                )))
            }
        }
        if (resource.version >= 4) shell.shell.listener.bounds(this)?.let { (w, h) -> resource.sendConfigureBounds(w, h) }
        val states = mutableListOf<Int>()
        if (activated) states += XdgToplevel.State.ACTIVATED
        if (maximized) states += XdgToplevel.State.MAXIMIZED
        if (fullscreen) states += XdgToplevel.State.FULLSCREEN
        if (resizing) states += XdgToplevel.State.RESIZING
        if (suspended && resource.version >= 6) states += XdgToplevel.State.SUSPENDED
        if (tiled && resource.version >= 2) {
            states += listOf(XdgToplevel.State.TILED_LEFT, XdgToplevel.State.TILED_RIGHT, XdgToplevel.State.TILED_TOP, XdgToplevel.State.TILED_BOTTOM)
        }
        windowsLog.trace { "Asking $this for ${width}x$height${if (states.isEmpty()) "" else ", states $states"}" }
        resource.sendConfigure(width, height, uintArray(states))
        shell.sendConfigure()
    }

    override val configuredSize: Pair<Int, Int> get() = configuredWidth to configuredHeight

    override val caughtUp: Boolean get() = shell.answeredAll

    override fun close() = resource.sendClose()

    private companion object {
        val VALID_EDGES = setOf(
            XdgToplevel.ResizeEdge.TOP, XdgToplevel.ResizeEdge.BOTTOM, XdgToplevel.ResizeEdge.LEFT,
            XdgToplevel.ResizeEdge.TOP_LEFT, XdgToplevel.ResizeEdge.BOTTOM_LEFT, XdgToplevel.ResizeEdge.RIGHT,
            XdgToplevel.ResizeEdge.TOP_RIGHT, XdgToplevel.ResizeEdge.BOTTOM_RIGHT, XdgToplevel.ResizeEdge.NONE,
        )
    }
}
