package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Our own things on screen: overlays that take over the input, on-screen displays, notices and
 * what their banners keep free; unmanaged surfaces; title bars' room.
 */
internal class DesktopOverlays(private val desktop: Desktop) {

    internal val unmanaged = LinkedHashMap<Surface, Pair<Int, Int>>()

    fun showUnmanaged(surface: Surface, x: Int, y: Int) {
        if (!surface.resource.isAlive) return
        if (unmanaged.remove(surface) == null) surface.resource.onDestroy { hideUnmanaged(surface) }
        unmanaged[surface] = x to y // last shown on top
        desktop.changed()
    }

    fun hideUnmanaged(surface: Surface) {
        if (unmanaged.remove(surface) == null) return
        if (desktop.seat.keyboardFocus === surface) focusUnmanaged(null)
        desktop.changed()
    }

    fun focusUnmanaged(surface: Surface?) {
        if (surface != null && surface !in unmanaged) return
        desktop.focusLog.trace { "Keyboard to ${surface?.roleObject ?: "the focused window"} (places itself)" }
        desktop.widgetHost.keyboardTo(surface ?: desktop.layerHost.exclusiveLayer()?.surface ?: desktop.focusedWindow?.surface)
    }

    fun showLevel(title: String, level: Double? = null, off: Boolean = false) {
        val own = desktop.osdHandler
        if (own != null && userCode("Your on-screen display", false) { own(title, level, off) }) return
        desktop.osd.show(Osd.Look(title, level, off))
        desktop.changed()
    }

    fun showChoices(title: String, choices: List<String>, chosen: Int) {
        desktop.osd.show(Osd.Look(title, null, false, choices, chosen))
        desktop.changed()
    }

    fun openOverlay(overlay: Overlay) {
        if (desktop.locked) {
            desktop.overlayLog.info { "Not opened while the session is locked: $overlay" }
            return
        }
        closeOverlay()
        desktop.overlay = overlay
        desktop.pointerGrab.end()
        desktop.overlayLog.info { "Open: $overlay" }
        desktop.seat.pointerMotion(0, null, 0.0, 0.0) // the program under the pointer loses it
        overlay.pointerMoved(desktop.pointerX, desktop.pointerY)
        desktop.changed()
        desktop.cursors.cursorChanged()
    }

    fun closeOverlay() {
        val open = desktop.overlay ?: return
        desktop.overlay = null
        brokenOverlay = null
        desktop.overlayLog.info { "Closed: $open" }
        desktop.changed()
        try {
            open.closed()
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.overlayLog.error("$open failed while closing", e)
        }
        desktop.pointerInput.movePointer(0, desktop.pointerX, desktop.pointerY) // the program under the pointer gets it back
        desktop.cursors.cursorChanged()
    }

    fun redrawOverlay() {
        if (desktop.overlay != null) desktop.changed()
    }

    // An overlay that failed to draw (closed before the next frame).
    internal var brokenOverlay: Overlay? = null

    // Runs [action] on the overlay; one that fails is closed (never the compositor with it).
    internal inline fun withOverlay(action: (Overlay) -> Unit): Boolean {
        val open = desktop.overlay ?: return false
        try {
            action(open)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.overlayLog.error("$open failed; closing it", e)
            closeOverlay()
        }
        return true
    }

    // [rect] without the strip banners take at the edge of [monitor].
    internal fun withoutBanners(rect: Rect, monitor: Monitor, room: Int, bottom: Boolean): Rect {
        if (room <= 0) return rect
        return if (bottom) {
            Rect(rect.x, rect.y, rect.width, (minOf(rect.bottom, monitor.area.bottom - room) - rect.y).coerceAtLeast(1))
        } else {
            val top = maxOf(rect.y, monitor.area.y + room)
            Rect(rect.x, top, rect.width, (rect.bottom - top).coerceAtLeast(1))
        }
    }

    fun titleHeight(window: Window): Int {
        if (window.fullscreen) return 0
        val mode = desktop.titleBarsOf(window)
        return when {
            mode == TitleBars.NONE -> 0
            window.needsTitleBar -> desktop.titleBarStyle.height
            window.serverDecorated && mode == TitleBars.COMPOSITOR -> desktop.titleBarStyle.height
            else -> 0
        }
    }

    // [area] without the title bar at its top (where the window's own picture goes).
    internal fun withoutTitle(window: Window, area: Rect): Rect {
        val bar = titleHeight(window)
        return if (bar == 0) area else Rect(area.x, area.y + bar, area.width, (area.height - bar).coerceAtLeast(1))
    }

    // A window's title bar came or went: its place changes.
    internal fun decorationOfChanged(window: Window) {
        val workspace = desktop.workspaceHost.workspaceOf(window) ?: return
        if (window.maximized && !window.fullscreen) desktop.windowRoles.applyState(window) else desktop.arranger.arrange(workspace)
        desktop.changed()
    }
}
