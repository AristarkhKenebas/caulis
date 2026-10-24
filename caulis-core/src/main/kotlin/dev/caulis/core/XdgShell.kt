package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.xdgshell.XdgPopup
import dev.caulis.protocol.xdgshell.XdgPositioner
import dev.caulis.protocol.xdgshell.XdgSurface
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.protocol.xdgshell.XdgWmBase
import dev.caulis.render.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * xdg-shell: how applications make windows (toplevels) and menus (popups).
 *
 * Every window goes through the same handshake: the client commits an empty surface,
 * we answer with a configure (the size we want), the client acknowledges it and draws.
 * Only then the window is shown ("mapped"). [listener] learns about windows and popups.
 */
class XdgShell(private val display: Display) {

    /** What the window manager is told. */
    interface Listener {
        /** A new window asks for its first size. Return the size (0, 0 = let the app choose). */
        fun initialSize(window: Window): Pair<Int, Int> = 0 to 0
        fun windowMapped(window: Window) {}
        fun windowUnmapped(window: Window) {}
        fun windowCommitted(window: Window) {}
        fun popupMapped(popup: Popup) {}
        fun popupUnmapped(popup: Popup) {}
        fun popupGrab(popup: Popup) {}
    }

    var listener: Listener = object : Listener {}

    init {
        display.addGlobal(XdgWmBase, VERSION) { wmBase -> wmBase.requests = wmBaseRequests }
    }

    private val wmBaseRequests = object : XdgWmBase.Requests {
        override fun destroy(resource: XdgWmBase) {}

        override fun createPositioner(resource: XdgWmBase, id: XdgPositioner) {
            Positioner(id)
        }

        override fun getXdgSurface(resource: XdgWmBase, id: XdgSurface, surface: WlSurface) {
            val target = surface.dataAs<Surface>()!!
            if (target.role != null && target.role != Surface.Role.XDG_TOPLEVEL && target.role != Surface.Role.XDG_POPUP) {
                resource.postError(XdgWmBase.Error.ROLE, "surface already has the role ${target.role}")
                return
            }
            if (target.current.buffer != null) {
                resource.postError(XdgWmBase.Error.INVALID_SURFACE_STATE, "surface already has a buffer")
                return
            }
            ShellSurface(id, target, this@XdgShell)
        }

        override fun pong(resource: XdgWmBase, serial: Int) {}
    }

    internal fun nextSerial() = display.nextSerial()

    companion object {
        const val VERSION = 6
    }
}

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

    private val sentSerials = ArrayList<Int>()
    private var acknowledged = false
    private var initialConfigureSent = false
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
                    is Window -> current.resource.isAlive
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
                role = Window(id, this@ShellSurface)
                surface.roleObject = role
            }

            override fun getPopup(resource: XdgSurface, id: XdgPopup, parent: XdgSurface?, positioner: XdgPositioner) {
                if (role != null) {
                    resource.postError(XdgSurface.Error.ALREADY_CONSTRUCTED, "already has a role")
                    return
                }
                val parentShell = parent?.dataAs<ShellSurface>()
                if (parentShell == null) {
                    // Popups without a parent are only for other shells (layer-shell); not supported yet.
                    resource.postError(XdgWmBase.Error.INVALID_POPUP_PARENT, "popups need a parent")
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
        surface.onCommit { committed() }
        resource.onDestroy { unmap() }
    }

    private fun committed() {
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
        resource.sendConfigure(serial)
    }
}

/** An application window (xdg_toplevel). */
class Window internal constructor(val resource: XdgToplevel, val shell: ShellSurface) {

    val surface: Surface get() = shell.surface

    var title = ""
        private set
    var appId = ""
        private set

    /** Size limits the app asked for (0 = no limit). */
    var minWidth = 0
        private set
    var minHeight = 0
        private set
    var maxWidth = 0
        private set
    var maxHeight = 0
        private set

    /** Where the window's visible top left corner is on the screen; set by the window manager. */
    var x = 0
    var y = 0

    /** True while the window has the keyboard focus (shown as the "active" window). */
    var activated = false
        private set

    private var configuredWidth = 0
    private var configuredHeight = 0

    init {
        resource.data = this
        resource.requests = object : XdgToplevel.Requests {
            override fun destroy(resource: XdgToplevel) = shell.unmap()

            override fun setParent(resource: XdgToplevel, parent: XdgToplevel?) {}

            override fun setTitle(resource: XdgToplevel, title: String) {
                this@Window.title = title
            }

            override fun setAppId(resource: XdgToplevel, appId: String) {
                this@Window.appId = appId
            }

            override fun showWindowMenu(resource: XdgToplevel, seat: WlSeat, serial: Int, x: Int, y: Int) {}

            // Interactive moving and resizing come with the window manager (stage 6).
            override fun move(resource: XdgToplevel, seat: WlSeat, serial: Int) {}

            override fun resize(resource: XdgToplevel, seat: WlSeat, serial: Int, edges: Int) {}

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

            override fun setMaximized(resource: XdgToplevel) {}

            override fun unsetMaximized(resource: XdgToplevel) {}

            override fun setFullscreen(resource: XdgToplevel, output: WlOutput?) {}

            override fun unsetFullscreen(resource: XdgToplevel) {}

            override fun setMinimized(resource: XdgToplevel) {}
        }
    }

    /** Asks the app to use this size (0 = its own choice) and tells it whether it is active. */
    fun configure(width: Int = configuredWidth, height: Int = configuredHeight) {
        configuredWidth = width
        configuredHeight = height
        val states = mutableListOf<Int>()
        if (activated) states += XdgToplevel.State.ACTIVATED
        resource.sendConfigure(width, height, uintArray(states))
        shell.sendConfigure()
    }

    /** Marks the window as active or not (apps draw active windows a bit differently). */
    fun setActivated(active: Boolean) {
        if (activated == active) return
        activated = active
        if (shell.mapped) configure()
    }

    /** Asks the app to close the window (it may show "save changes?" first). */
    fun close() = resource.sendClose()

    override fun toString() = "Window(\"$title\", $appId)"
}

/** A menu or tooltip attached to a window or another popup (xdg_popup). */
class Popup internal constructor(
    val resource: XdgPopup,
    val shell: ShellSurface,
    val parent: ShellSurface,
    private var rules: Positioner.Rules,
) {
    val surface: Surface get() = shell.surface

    /** Position of the popup's visible area relative to the parent's visible area. */
    var x = 0
        private set
    var y = 0
        private set

    init {
        resource.data = this
        resource.requests = object : XdgPopup.Requests {
            override fun destroy(resource: XdgPopup) = shell.unmap()

            override fun grab(resource: XdgPopup, seat: WlSeat, serial: Int) {
                shell.shell.listener.popupGrab(this@Popup)
            }

            override fun reposition(resource: XdgPopup, positioner: XdgPositioner, token: Int) {
                rules = positioner.dataAs<Positioner>()!!.copy()
                resource.sendRepositioned(token)
                configure()
            }
        }
    }

    internal fun configure() {
        val (px, py) = rules.place()
        x = px
        y = py
        resource.sendConfigure(x, y, rules.width, rules.height)
        shell.sendConfigure()
    }

    /** Closes the popup (e.g. the user clicked elsewhere). */
    fun dismiss() = resource.sendPopupDone()
}

/** The rules a client gives for placing a popup (xdg_positioner). */
class Positioner internal constructor(resource: XdgPositioner) {

    private val rules = Rules()
    val width: Int get() = rules.width
    val height: Int get() = rules.height

    class Rules(
        var width: Int = 0,
        var height: Int = 0,
        var anchorRect: Rect = Rect(0, 0, 0, 0),
        var anchor: Int = XdgPositioner.Anchor.NONE,
        var gravity: Int = XdgPositioner.Gravity.NONE,
        var offsetX: Int = 0,
        var offsetY: Int = 0,
    ) {
        fun copy() = Rules(width, height, anchorRect, anchor, gravity, offsetX, offsetY)

        /**
         * Top left corner of the popup relative to the parent: take the anchor point on the
         * anchor rectangle, then grow the popup from it in the gravity direction.
         * (Moving popups back on screen when they do not fit comes later.)
         */
        fun place(): Pair<Int, Int> {
            val r = anchorRect
            val anchorX = when (anchor) {
                XdgPositioner.Anchor.LEFT, XdgPositioner.Anchor.TOP_LEFT, XdgPositioner.Anchor.BOTTOM_LEFT -> r.x
                XdgPositioner.Anchor.RIGHT, XdgPositioner.Anchor.TOP_RIGHT, XdgPositioner.Anchor.BOTTOM_RIGHT -> r.x + r.width
                else -> r.x + r.width / 2
            }
            val anchorY = when (anchor) {
                XdgPositioner.Anchor.TOP, XdgPositioner.Anchor.TOP_LEFT, XdgPositioner.Anchor.TOP_RIGHT -> r.y
                XdgPositioner.Anchor.BOTTOM, XdgPositioner.Anchor.BOTTOM_LEFT, XdgPositioner.Anchor.BOTTOM_RIGHT -> r.y + r.height
                else -> r.y + r.height / 2
            }
            val x = when (gravity) {
                XdgPositioner.Gravity.LEFT, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.BOTTOM_LEFT -> anchorX - width
                XdgPositioner.Gravity.RIGHT, XdgPositioner.Gravity.TOP_RIGHT, XdgPositioner.Gravity.BOTTOM_RIGHT -> anchorX
                else -> anchorX - width / 2
            }
            val y = when (gravity) {
                XdgPositioner.Gravity.TOP, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.TOP_RIGHT -> anchorY - height
                XdgPositioner.Gravity.BOTTOM, XdgPositioner.Gravity.BOTTOM_LEFT, XdgPositioner.Gravity.BOTTOM_RIGHT -> anchorY
                else -> anchorY - height / 2
            }
            return (x + offsetX) to (y + offsetY)
        }
    }

    fun copy() = rules.copy()

    init {
        resource.data = this
        resource.requests = object : XdgPositioner.Requests {
            override fun destroy(resource: XdgPositioner) {}

            override fun setSize(resource: XdgPositioner, width: Int, height: Int) {
                if (width <= 0 || height <= 0) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "size must be positive")
                    return
                }
                rules.width = width
                rules.height = height
            }

            override fun setAnchorRect(resource: XdgPositioner, x: Int, y: Int, width: Int, height: Int) {
                if (width < 0 || height < 0) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "anchor rectangle size must not be negative")
                    return
                }
                rules.anchorRect = Rect(x, y, width, height)
            }

            override fun setAnchor(resource: XdgPositioner, anchor: Int) {
                rules.anchor = anchor
            }

            override fun setGravity(resource: XdgPositioner, gravity: Int) {
                rules.gravity = gravity
            }

            override fun setConstraintAdjustment(resource: XdgPositioner, constraintAdjustment: Int) {}

            override fun setOffset(resource: XdgPositioner, x: Int, y: Int) {
                rules.offsetX = x
                rules.offsetY = y
            }

            override fun setReactive(resource: XdgPositioner) {}

            override fun setParentSize(resource: XdgPositioner, parentWidth: Int, parentHeight: Int) {}

            override fun setParentConfigure(resource: XdgPositioner, serial: Int) {}
        }
    }
}

/** A list of numbers as the byte array the protocol uses for `array` arguments. */
internal fun uintArray(values: List<Int>): ByteArray {
    val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
    values.forEach { bytes.putInt(it) }
    return bytes.array()
}
