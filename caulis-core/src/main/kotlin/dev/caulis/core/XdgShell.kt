package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.xdgshell.XdgPopup
import dev.caulis.protocol.xdgshell.XdgPositioner
import dev.caulis.protocol.xdgshell.XdgSurface
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.protocol.xdgshell.XdgWmBase
import dev.caulis.tiling.LayoutWindow
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

        /** [window] is gone for good (also one that never appeared): forget everything about it. */
        fun windowDestroyed(window: Window) {}
        fun windowCommitted(window: Window) {}

        /** The window's title or app id changed. */
        fun windowInfoChanged(window: Window) {}

        /** The largest sensible window size (e.g. the screen), or null if unknown. */
        fun bounds(window: Window): Pair<Int, Int>? = null

        /** The app asks to move the window with the pointer (the user dragged its title bar). */
        fun moveRequested(window: Window, serial: Int) {}

        /** The app asks to resize with the pointer; [edges] says which (XdgToplevel.ResizeEdge). */
        fun resizeRequested(window: Window, serial: Int, edges: Int) {}

        fun maximizeRequested(window: Window, maximized: Boolean) {}
        fun fullscreenRequested(window: Window, fullscreen: Boolean) {}
        fun minimizeRequested(window: Window) {}

        /**
         * Where [popup] must stay — the monitor its window is on — relative to its parent's
         * visible top left corner (as the popup's own place is), or null: anywhere.
         */
        fun popupBounds(popup: Popup): Rect? = null

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
        surface.onCommit { committed() }
        resource.onDestroy { roleDestroyed() }
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
        resource.sendConfigure(serial)
    }
}

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
                if (title == this@XdgWindow.title) return
                this@XdgWindow.title = title
                shell.shell.listener.windowInfoChanged(this@XdgWindow)
            }

            override fun setAppId(resource: XdgToplevel, appId: String) {
                if (appId == this@XdgWindow.appId) return
                this@XdgWindow.appId = appId
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
        if (tiled && resource.version >= 2) {
            states += listOf(XdgToplevel.State.TILED_LEFT, XdgToplevel.State.TILED_RIGHT, XdgToplevel.State.TILED_TOP, XdgToplevel.State.TILED_BOTTOM)
        }
        windowsLog.trace { "Asking $this for ${width}x$height${if (states.isEmpty()) "" else ", states $states"}" }
        resource.sendConfigure(width, height, uintArray(states))
        shell.sendConfigure()
    }

    override val configuredSize: Pair<Int, Int> get() = configuredWidth to configuredHeight

    override fun close() = resource.sendClose()

    private companion object {
        val VALID_EDGES = setOf(
            XdgToplevel.ResizeEdge.TOP, XdgToplevel.ResizeEdge.BOTTOM, XdgToplevel.ResizeEdge.LEFT,
            XdgToplevel.ResizeEdge.TOP_LEFT, XdgToplevel.ResizeEdge.BOTTOM_LEFT, XdgToplevel.ResizeEdge.RIGHT,
            XdgToplevel.ResizeEdge.TOP_RIGHT, XdgToplevel.ResizeEdge.BOTTOM_RIGHT, XdgToplevel.ResizeEdge.NONE,
        )
    }
}

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
        val box = rules.place(shell.shell.listener.popupBounds(this))
        x = box.x
        y = box.y
        resource.sendConfigure(box.x, box.y, box.width, box.height)
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

    internal class Rules(
        var width: Int = 0,
        var height: Int = 0,
        var anchorRect: Rect = Rect(0, 0, 0, 0),
        var anchor: Int = XdgPositioner.Anchor.NONE,
        var gravity: Int = XdgPositioner.Gravity.NONE,
        var offsetX: Int = 0,
        var offsetY: Int = 0,
        /** What may be done when it does not fit (XdgPositioner.ConstraintAdjustment bits). */
        var adjustment: Int = 0,
    ) {
        fun copy() = Rules(width, height, anchorRect, anchor, gravity, offsetX, offsetY, adjustment)

        /**
         * Where the popup goes, relative to the parent, kept inside [bounds] (same
         * coordinates; null: anywhere) as far as the program allows: first turned to the
         * other side (a menu near the bottom opens upwards), then slid along the edge, then
         * made smaller — each only if the program said it may be.
         */
        fun place(bounds: Rect?): Rect {
            var box = Rect(place().first, place().second, width, height)
            if (bounds == null || bounds.isEmpty) return box
            fun fitsX(r: Rect) = r.x >= bounds.x && r.right <= bounds.right
            fun fitsY(r: Rect) = r.y >= bounds.y && r.bottom <= bounds.bottom
            // Turned over: anchor and gravity to the other side, the offset too.
            if (!fitsX(box) && adjustment and FLIP_X != 0) {
                val turned = Rules(width, height, anchorRect, flipX(anchor), flipX(gravity), -offsetX, offsetY).place()
                val other = Rect(turned.first, box.y, width, height)
                if (fitsX(other)) box = other
            }
            if (!fitsY(box) && adjustment and FLIP_Y != 0) {
                val turned = Rules(width, height, anchorRect, flipY(anchor), flipY(gravity), offsetX, -offsetY).place()
                val other = Rect(box.x, turned.second, width, height)
                if (fitsY(other)) box = other
            }
            // Slid along the edge (its start stays inside when it is bigger than the room).
            if (!fitsX(box) && adjustment and SLIDE_X != 0) {
                val x = if (box.right > bounds.right) bounds.right - box.width else box.x
                box = Rect(maxOf(x, bounds.x), box.y, box.width, box.height)
            }
            if (!fitsY(box) && adjustment and SLIDE_Y != 0) {
                val y = if (box.bottom > bounds.bottom) bounds.bottom - box.height else box.y
                box = Rect(box.x, maxOf(y, bounds.y), box.width, box.height)
            }
            // Made smaller to what is left.
            if (!fitsX(box) && adjustment and RESIZE_X != 0) {
                val left = maxOf(box.x, bounds.x)
                val right = minOf(box.right, bounds.right)
                if (right > left) box = Rect(left, box.y, right - left, box.height)
            }
            if (!fitsY(box) && adjustment and RESIZE_Y != 0) {
                val top = maxOf(box.y, bounds.y)
                val bottom = minOf(box.bottom, bounds.bottom)
                if (bottom > top) box = Rect(box.x, top, box.width, bottom - top)
            }
            return box
        }

        private fun flipX(edge: Int): Int = when (edge) {
            XdgPositioner.Anchor.LEFT -> XdgPositioner.Anchor.RIGHT
            XdgPositioner.Anchor.RIGHT -> XdgPositioner.Anchor.LEFT
            XdgPositioner.Anchor.TOP_LEFT -> XdgPositioner.Anchor.TOP_RIGHT
            XdgPositioner.Anchor.TOP_RIGHT -> XdgPositioner.Anchor.TOP_LEFT
            XdgPositioner.Anchor.BOTTOM_LEFT -> XdgPositioner.Anchor.BOTTOM_RIGHT
            XdgPositioner.Anchor.BOTTOM_RIGHT -> XdgPositioner.Anchor.BOTTOM_LEFT
            else -> edge
        }

        private fun flipY(edge: Int): Int = when (edge) {
            XdgPositioner.Anchor.TOP -> XdgPositioner.Anchor.BOTTOM
            XdgPositioner.Anchor.BOTTOM -> XdgPositioner.Anchor.TOP
            XdgPositioner.Anchor.TOP_LEFT -> XdgPositioner.Anchor.BOTTOM_LEFT
            XdgPositioner.Anchor.BOTTOM_LEFT -> XdgPositioner.Anchor.TOP_LEFT
            XdgPositioner.Anchor.TOP_RIGHT -> XdgPositioner.Anchor.BOTTOM_RIGHT
            XdgPositioner.Anchor.BOTTOM_RIGHT -> XdgPositioner.Anchor.TOP_RIGHT
            else -> edge
        }

        /**
         * Top left corner of the popup relative to the parent, without bounds: take the
         * anchor point on the anchor rectangle, then grow the popup from it in the gravity
         * direction.
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

    internal fun copy() = rules.copy()

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
                if (anchor !in 0..8) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "anchor $anchor is not one of the nine")
                    return
                }
                rules.anchor = anchor
            }

            override fun setGravity(resource: XdgPositioner, gravity: Int) {
                if (gravity !in 0..8) {
                    resource.postError(XdgPositioner.Error.INVALID_INPUT, "gravity $gravity is not one of the nine")
                    return
                }
                rules.gravity = gravity
            }

            override fun setConstraintAdjustment(resource: XdgPositioner, constraintAdjustment: Int) {
                rules.adjustment = constraintAdjustment
            }

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

// xdg_positioner.constraint_adjustment bits.
private const val SLIDE_X = 1
private const val SLIDE_Y = 2
private const val FLIP_X = 4
private const val FLIP_Y = 8
private const val RESIZE_X = 16
private const val RESIZE_Y = 32

/** A list of numbers as the byte array the protocol uses for `array` arguments. */
internal fun uintArray(values: List<Int>): ByteArray {
    val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
    values.forEach { bytes.putInt(it) }
    return bytes.array()
}

private val windowsLog = dev.caulis.log.Logger("windows")
