package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgPopup
import dev.caulis.protocol.xdgshell.XdgPositioner
import dev.caulis.protocol.xdgshell.XdgSurface
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.protocol.xdgshell.XdgWmBase

/**
 * The part common to windows and popups (xdg_surface): the configure handshake and the
 * window geometry (the visible part of the surface, without client-side shadows).
 */
class ShellSurface internal constructor(
    val resource: XdgSurface,
    val surface: Surface,
    internal val shell: XdgShell,
) {
    /** A Window or Popup once the client chose. */
    var role: Any? = null
        private set

    // Its toplevel or popup was destroyed (or the xdg_surface itself): commits do nothing more,
    // so a window can never come back from the dead.
    internal var roleGone = false
        private set

    // Unmaps it and makes it inert for good (its role object or the xdg_surface went away).
    internal fun roleDestroyed() {
        if (roleGone) return
        unmap()
        roleGone = true
        (role as? Window)?.let { shell.listener.windowDestroyed(it) }
    }

    private val sentSerials = ArrayList<Int>()

    // True when every configure sent was acknowledged.
    internal val answeredAll: Boolean get() = sentSerials.isEmpty()
    private var acknowledged = false
    private var initialConfigureSent = false

    /** True once the window manager has answered the app's first commit (sizes may be sent from now on). */
    val configured: Boolean get() = initialConfigureSent
    var mapped = false
        private set

    private var pendingGeometry: Rect? = null

    /** The window's visible rectangle within the surface; null = the whole surface. */
    var geometry: Rect? = null
        private set

    /** The visible rectangle, in surface coordinates. */
    val visibleRect: Rect get() = geometry ?: Rect(0, 0, surface.width, surface.height)

    init {
        resource.data = this
        resource.requests = object : XdgSurface.Requests {
            override fun destroy(resource: XdgSurface) {
                val roleStillAlive = when (val current = role) {
                    is XdgWindow -> current.resource.isAlive
                    is Popup -> current.resource.isAlive
                    else -> false
                }
                if (roleStillAlive) {
                    resource.postError(XdgSurface.Error.DEFUNCT_ROLE_OBJECT, "destroy the toplevel or popup first")
                }
            }

            override fun getToplevel(resource: XdgSurface, id: XdgToplevel) {
                if (role != null) {
                    resource.postError(XdgSurface.Error.ALREADY_CONSTRUCTED, "already has a role")
                    return
                }
                if (!surface.assignRole(Surface.Role.XDG_TOPLEVEL, resource, XdgWmBase.Error.ROLE)) return
                role = XdgWindow(id, this@ShellSurface)
                surface.roleObject = role
            }

            override fun getPopup(resource: XdgSurface, id: XdgPopup, parent: XdgSurface?, positioner: XdgPositioner) {
                if (role != null) {
                    resource.postError(XdgSurface.Error.ALREADY_CONSTRUCTED, "already has a role")
                    return
                }
                // Without a parent, another shell adopts it before its first commit (a panel's
                // menu: zwlr_layer_surface_v1.get_popup).
                val parentShell = parent?.dataAs<ShellSurface>()
                // The parent must be a toplevel or popup already (so a popup can never be its
                // own parent, or its parent's parent: no cycles to follow forever).
                if (parent != null && (parentShell == null || parentShell.role == null || parentShell.roleGone)) {
                    resource.postError(XdgWmBase.Error.INVALID_POPUP_PARENT, "the parent is not a toplevel or popup")
                    return
                }
                val rules = positioner.dataAs<Positioner>()!!
                if (rules.width <= 0 || rules.height <= 0) {
                    resource.postError(XdgWmBase.Error.INVALID_POSITIONER, "positioner has no size")
                    return
                }
                if (!surface.assignRole(Surface.Role.XDG_POPUP, resource, XdgWmBase.Error.ROLE)) return
                role = Popup(id, this@ShellSurface, parentShell, rules.copy())
                surface.roleObject = role
            }

            override fun setWindowGeometry(resource: XdgSurface, x: Int, y: Int, width: Int, height: Int) {
                if (width <= 0 || height <= 0) {
                    resource.postError(XdgSurface.Error.INVALID_SIZE, "window geometry must have a positive size")
                    return
                }
                pendingGeometry = Rect(x, y, width, height)
            }

            override fun ackConfigure(resource: XdgSurface, serial: Int) {
                val index = sentSerials.indexOf(serial)
                if (index < 0) {
                    resource.postError(XdgSurface.Error.INVALID_SERIAL, "serial $serial was never sent")
                    return
                }
                // Acknowledging one configure also covers all older ones.
                repeat(index + 1) { sentSerials.removeFirst() }
                acknowledged = true
            }
        }
        surface.shellSurface = this
        surface.onCommit { committed() }
        resource.onDestroy {
            roleDestroyed()
            if (surface.shellSurface === this) surface.shellSurface = null
        }
    }

    private fun committed() {
        if (roleGone) return
        val currentRole = role ?: return
        pendingGeometry?.let { geometry = it; pendingGeometry = null }

        if (surface.current.buffer != null && !acknowledged) {
            resource.postError(XdgSurface.Error.UNCONFIGURED_BUFFER, "a buffer was committed before the first configure was acknowledged")
            return
        }
        if (!initialConfigureSent) {
            // The first commit (without a buffer) asks for the initial configure.
            initialConfigureSent = true
            when (currentRole) {
                is Window -> {
                    val (width, height) = shell.listener.initialSize(currentRole)
                    currentRole.configure(width, height)
                }
                is Popup -> currentRole.configure()
            }
            return
        }
        when {
            surface.hasContent && !mapped -> {
                mapped = true
                when (currentRole) {
                    is Window -> shell.listener.windowMapped(currentRole)
                    is Popup -> shell.listener.popupMapped(currentRole)
                }
            }
            !surface.hasContent && mapped -> {
                unmap()
                // After unmapping, the client starts over with an empty commit.
                initialConfigureSent = false
                acknowledged = false
            }
            mapped && currentRole is Window -> shell.listener.windowCommitted(currentRole)
        }
    }

    internal fun unmap() {
        if (!mapped) return
        mapped = false
        when (val currentRole = role) {
            is Window -> shell.listener.windowUnmapped(currentRole)
            is Popup -> shell.listener.popupUnmapped(currentRole)
        }
    }

    /** Sends xdg_surface.configure, which ends a group of configure events. */
    internal fun sendConfigure() {
        val serial = shell.nextSerial()
        sentSerials += serial
        // (A program that never answers keeps only the newest ones: the list does not grow for ever.)
        while (sentSerials.size > MAX_UNANSWERED_CONFIGURES) sentSerials.removeFirst()
        resource.sendConfigure(serial)
    }
}
