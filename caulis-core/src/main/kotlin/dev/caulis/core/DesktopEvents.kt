package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * What your code can listen to (windows opening and closing, the focus, workspaces, power), and
 * the window list for docks, with where a dock wants a window to minimize to.
 */
internal class DesktopEvents(private val desktop: Desktop) {

    internal val powerListeners = ArrayList<(Monitor) -> Unit>()

    fun onPowerChanged(listener: (Monitor) -> Unit): AutoCloseable {
        powerListeners += listener
        return AutoCloseable { powerListeners -= listener }
    }

    private val windowListListeners = ArrayList<() -> Unit>()

    private var windowListPending = false

    fun onWindowListChanged(listener: () -> Unit): AutoCloseable {
        windowListListeners += listener
        return AutoCloseable { windowListListeners -= listener }
    }

    internal fun windowListChanged() {
        if (windowListPending || windowListListeners.isEmpty()) return
        windowListPending = true
        desktop.compositor.display.loop.post {
            windowListPending = false
            for (listener in windowListListeners.toList()) {
                try {
                    listener()
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    desktop.log.error("A window list listener failed (ignored): $e", e)
                }
            }
        }
    }

    // Where each window goes when minimized: a rectangle in a surface (a dock's icon).
    internal val minimizeTargets = HashMap<Window, Pair<Surface, Rect>>()

    fun setMinimizeTarget(window: Window, surface: Surface?, rect: Rect?) {
        if (surface == null || rect == null || rect.isEmpty) minimizeTargets -= window else minimizeTargets[window] = surface to rect
    }

    // The window's minimized place on screen: the dock icon, or a sliver at its bottom.
    internal fun minimizedRect(window: Window, from: Rect): Rect {
        minimizeTargets[window]?.let { (surface, rect) ->
            surfaceOrigin(surface)?.let { (x, y) -> return rect.translate(x, y) }
        }
        val width = (from.width / 4).coerceAtLeast(1)
        val height = (from.height / 8).coerceAtLeast(1)
        return Rect(from.x + (from.width - width) / 2, from.bottom - height, width, height)
    }

    // Where a surface's top left corner is on screen (null if it is not shown).
    internal fun surfaceOrigin(surface: Surface): Pair<Int, Int>? {
        if (!surface.resource.isAlive) return null
        var current = surface
        var dx = 0
        var dy = 0
        while (true) {
            val sub = current.subsurface ?: break
            dx += sub.x
            dy += sub.y
            current = sub.parent ?: return null
        }
        return when (val role = current.roleObject) {
            is LayerSurface -> (role.x + dx) to (role.y + dy)
            is Window -> desktop.animator.screenRects[role]?.let { (it.x - role.visibleRect.x + dx) to (it.y - role.visibleRect.y + dy) }
            else -> null
        }
    }

    internal val windowOpenedListeners = ArrayList<(Window) -> Unit>()

    internal val windowClosedListeners = ArrayList<(Window) -> Unit>()

    internal val focusListeners = ArrayList<(Window?) -> Unit>()

    internal val workspaceListeners = ArrayList<(Workspace) -> Unit>()

    internal val windowInfoListeners = ArrayList<(Window) -> Unit>()

    fun onWindowInfoChanged(listener: (Window) -> Unit): AutoCloseable {
        windowInfoListeners += listener
        return AutoCloseable { windowInfoListeners -= listener }
    }

    fun onWindowOpened(listener: (Window) -> Unit): AutoCloseable {
        windowOpenedListeners += listener
        return AutoCloseable { windowOpenedListeners -= listener }
    }

    fun onWindowClosed(listener: (Window) -> Unit): AutoCloseable {
        windowClosedListeners += listener
        return AutoCloseable { windowClosedListeners -= listener }
    }

    fun onFocusChanged(listener: (Window?) -> Unit): AutoCloseable {
        focusListeners += listener
        return AutoCloseable { focusListeners -= listener }
    }

    fun onWorkspaceChanged(listener: (Workspace) -> Unit): AutoCloseable {
        workspaceListeners += listener
        return AutoCloseable { workspaceListeners -= listener }
    }
}
