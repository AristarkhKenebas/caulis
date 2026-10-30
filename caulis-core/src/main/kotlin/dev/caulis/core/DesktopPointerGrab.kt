package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel

/**
 * The desktop moving or resizing a window with the pointer, while the button that started it
 * is held ([Desktop.beginMove], [Desktop.beginResize], or a program asking from its title
 * bar). Programs do not see the pointer meanwhile. It lives as long as its desktop.
 */
internal class DesktopPointerGrab(private val desktop: Desktop, private val seat: Seat) {

    sealed class Grab(val window: Window, val pointerX: Double, val pointerY: Double) {
        class Move(window: Window, pointerX: Double, pointerY: Double, val startX: Int, val startY: Int) :
            Grab(window, pointerX, pointerY)

        class Resize(window: Window, pointerX: Double, pointerY: Double, val edges: Int, val start: Rect) :
            Grab(window, pointerX, pointerY) {
            var lastWidth = start.width
            var lastHeight = start.height
        }
    }

    /** The move or resize going on, or null. */
    var current: Grab? = null
        private set

    /** [window] is gone (closed, minimized): a move or resize of it ends without a word. */
    fun forget(window: Window) {
        if (current?.window === window) current = null
    }

    fun beginMove(window: Window) {
        // A window of a shown special workspace: dragged over its tile, dropped outside it moves out.
        if (!desktop.locked && desktop.buttonsHeld > 0 && desktop.scratchpadOf(window)?.let { it.isSpecial && it.shown && !it.leaving } == true) {
            desktop.stopGlide(window)
            start(Grab.Move(window, desktop.pointerX, desktop.pointerY, window.x, window.y))
            return
        }
        if (desktop.locked || desktop.buttonsHeld == 0 || window !in desktop.windows || window.fullscreen) return
        if (window.maximized) {
            // Dragging a maximized window: it gets its old size back, under the pointer.
            val floating = desktop.floatingRects[window] ?: desktop.currentRect(window)
            desktop.floatingRects[window] = Rect((desktop.pointerX - floating.width / 2).toInt(), desktop.pointerY.toInt() - 10, floating.width, floating.height)
            desktop.setMaximized(window, false)
        }
        if (window.tiled) {
            desktop.floatingRects[window] = desktop.currentRect(window) // it floats from where it is now
            desktop.setFloating(window, true)
        }
        start(Grab.Move(window, desktop.pointerX, desktop.pointerY, window.x, window.y))
    }

    fun beginResize(window: Window, edges: Int) {
        if (desktop.locked || desktop.buttonsHeld == 0 || window !in desktop.windows || window.maximized || window.fullscreen) return
        if (window.tiled) {
            desktop.floatingRects[window] = desktop.currentRect(window)
            desktop.setFloating(window, true)
        }
        start(Grab.Resize(window, desktop.pointerX, desktop.pointerY, edges, desktop.currentRect(window)))
    }

    private fun start(newGrab: Grab) {
        current = newGrab
        desktop.focus(newGrab.window)
        // The app under the pointer stops getting pointer events during the grab.
        seat.pointerMotion((System.nanoTime() / 1_000_000).toInt(), null, 0.0, 0.0)
    }

    /** The pointer moved: the window follows (a move) or changes its size (a resize). */
    fun pointerMoved(current: Grab) {
        val dx = (desktop.pointerX - current.pointerX).toInt()
        val dy = (desktop.pointerY - current.pointerY).toInt()
        when (current) {
            is Grab.Move -> {
                current.window.x = current.startX + dx
                current.window.y = current.startY + dy
                desktop.requestRedraw()
            }
            is Grab.Resize -> {
                val window = current.window
                val edges = current.edges
                var newWidth = current.start.width
                var newHeight = current.start.height
                if (edges and XdgToplevel.ResizeEdge.LEFT != 0) newWidth -= dx
                if (edges and XdgToplevel.ResizeEdge.RIGHT != 0) newWidth += dx
                if (edges and XdgToplevel.ResizeEdge.TOP != 0) newHeight -= dy
                if (edges and XdgToplevel.ResizeEdge.BOTTOM != 0) newHeight += dy
                newWidth = limitSize(newWidth, window.minWidth, window.maxWidth)
                newHeight = limitSize(newHeight, window.minHeight, window.maxHeight)
                desktop.behaviorOf(window).limit(newWidth, newHeight).let { (w, h) -> newWidth = w; newHeight = h }
                if (newWidth != current.lastWidth || newHeight != current.lastHeight) {
                    current.lastWidth = newWidth
                    current.lastHeight = newHeight
                    window.resizing = true
                    window.configure(newWidth, newHeight)
                }
            }
        }
    }

    /** The button is let go (or something else ended it): the window stays where it is now. */
    fun end() {
        val ended = current ?: return
        current = null
        if (ended is Grab.Move && desktop.scratchpadOf(ended.window)?.isSpecial == true) {
            desktop.dropFromSpecialWorkspace(ended.window, desktop.pointerX, desktop.pointerY)
            return
        }
        if (ended is Grab.Move) {
            desktop.floatingRects[ended.window] = desktop.currentRect(ended.window)
            // Dropped on another monitor: it belongs to the workspace shown there now.
            val rect = desktop.currentRect(ended.window)
            val target = desktop.monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            val from = desktop.monitorOf(ended.window)
            if (target !== from && desktop.workspaceOf(ended.window) != null) {
                desktop.moveToWorkspace(ended.window, target.workspace)
                desktop.setFocusedMonitor(target)
                desktop.focus(ended.window)
            }
        }
        if (ended is Grab.Resize) {
            if (ended.window.resizing) {
                ended.window.resizing = false
                ended.window.configure()
            }
            desktop.floatingRects[ended.window] = desktop.currentRect(ended.window)
        }
    }
}

// A size within the app's limits (0 = no limit), never smaller than 1.
internal fun limitSize(size: Int, min: Int, max: Int): Int {
    var result = maxOf(size, min, 1)
    if (max > 0) result = minOf(result, max)
    return result
}
