package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.xdgshell.XdgPositioner
import dev.caulis.protocol.xdgshell.XdgSurface
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.protocol.xdgshell.XdgWmBase
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
        /**
         * [popup] wants the keyboard while it is open (a menu). [serial] is the input event the
         * program answers (see [dev.caulis.core.input.Seat.isInputSerial]); refuse by
         * dismissing the popup. By default it calls the old [popupGrab] without the serial (a
         * listener written for Caulis 0.1.1 still hears it).
         */
        @Suppress("DEPRECATION")
        fun popupGrab(popup: Popup, serial: Int) = popupGrab(popup)

        /** Caulis 0.1.1's popupGrab, without the serial (called by the new one unless that is overridden). */
        @Deprecated("Override popupGrab(popup, serial) and check the serial")
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
            // One xdg_surface at a time (two would make two windows of one surface).
            if (target.shellSurface?.resource?.isAlive == true) {
                resource.postError(XdgWmBase.Error.ROLE, "the surface already has an xdg_surface")
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

// Configures kept waiting for the program's answer.
internal const val MAX_UNANSWERED_CONFIGURES = 128

// xdg_positioner.constraint_adjustment bits.
internal const val SLIDE_X = 1
internal const val SLIDE_Y = 2
internal const val FLIP_X = 4
internal const val FLIP_Y = 8
internal const val RESIZE_X = 16
internal const val RESIZE_Y = 32

/** A list of numbers as the byte array the protocol uses for `array` arguments. */
internal fun uintArray(values: List<Int>): ByteArray {
    val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
    values.forEach { bytes.putInt(it) }
    return bytes.array()
}

internal val windowsLog = dev.caulis.log.Logger("windows")
