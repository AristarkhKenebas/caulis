package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.GpuTexture

/**
 * The desktop's own title bars ([TitleBars.COMPOSITOR]): their pictures, and clicks on them
 * and on a floating window's edges (buttons, dragging, a double click). It lives as long as
 * its desktop.
 */
internal class DesktopTitleBars(private val desktop: Desktop) : AutoCloseable {

    // Each window's drawn title bar and what it showed (redrawn only when that changes).
    private val textures = HashMap<Window, Pair<Any, GpuTexture>>()

    /** The title bar (and its button, if any) the pointer is over. */
    var hover: Pair<Window, TitleButton?>? = null
    private var pressed: Pair<Window, TitleButton>? = null

    /** [window]'s title bar, drawn at [bar]'s size (again only when what it shows changed). */
    fun texture(window: Window, bar: Rect): GpuTexture {
        // At the scale of the window's monitor.
        val scale = desktop.monitorOf(window).scale
        fun px(value: Int) = Math.round(value * scale).toInt()
        val pixels = desktop.pixelsOf(Rect(0, 0, bar.width, desktop.titleBarStyle.height), scale)
        val hovered = hover?.takeIf { it.first === window }?.second
        val pressed = this.pressed?.takeIf { it.first === window }?.second
        val key = listOf(window.title, window.appId, window === desktop.focusedWindow, window.maximized, hovered, pressed, pixels.width, pixels.height, scale)
        textures[window]?.let { (shown, texture) -> if (shown == key) return texture else texture.close() }
        val look = TitleBarLook(
            pixels.width, pixels.height, scale, window.title, window.appId, window === desktop.focusedWindow, window.maximized, hovered, pressed,
            desktop.titleBarStyle.buttonPlaces(bar.width).map { (button, range) -> button to (px(range.first) until px(range.last + 1)) },
            desktop.titleBarStyle,
        )
        val image = java.awt.image.BufferedImage(pixels.width.coerceAtLeast(1), pixels.height.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            desktop.titleBarStyle.painter.paint(g, look)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Drawing a title bar failed (your TitleBarPainter?)", e)
        } finally {
            g.dispose()
        }
        val texture = desktop.renderer.createTexture(image.width, image.height)
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *data), 0, image.height)
        }
        textures[window] = key to texture
        return texture
    }

    /** What of a compositor title bar (or a floating window's edge) is at a point. */
    sealed class Decoration(val window: Window) {
        class Bar(window: Window, val button: TitleButton?) : Decoration(window)
        class Edge(window: Window, val edges: Int) : Decoration(window)
    }

    private fun barAt(window: Window, x: Double, y: Double): Decoration? {
        val content = desktop.screenRects[window] ?: return null
        val height = desktop.titleHeight(window)
        if (height > 0 && x >= content.x && x < content.right && y >= content.y - height && y < content.y) {
            val button = desktop.titleBarStyle.buttonPlaces(content.width).firstOrNull { (_, range) -> (x - content.x).toInt() in range }?.first
            return Decoration.Bar(window, button)
        }
        // Floating windows with our title bar can be resized at their edges.
        if (height > 0 && !window.tiled && !window.maximized) {
            val frame = Rect(content.x, content.y - height, content.width, content.height + height)
            val b = desktop.resizeBorder
            if (x >= frame.x - b && x < frame.right + b && y >= frame.y - b && y < frame.bottom + b && !(x >= frame.x && x < frame.right && y >= frame.y && y < frame.bottom)) {
                var edges = 0
                if (x < frame.x) edges = edges or XdgToplevel.ResizeEdge.LEFT
                if (x >= frame.right) edges = edges or XdgToplevel.ResizeEdge.RIGHT
                if (y < frame.y) edges = edges or XdgToplevel.ResizeEdge.TOP
                if (y >= frame.bottom) edges = edges or XdgToplevel.ResizeEdge.BOTTOM
                return Decoration.Edge(window, edges)
            }
        }
        return null
    }

    private var lastBarClick = 0L

    /** A press or release on a title bar: buttons, dragging, double click. True if it was ours. */
    fun button(button: Int, pressed: Boolean): Boolean {
        val held = this.pressed
        if (!pressed) {
            if (held == null) return false
            this.pressed = null
            // A button acts when let go over it.
            val over = at(desktop.pointerX, desktop.pointerY) as? Decoration.Bar
            if (over != null && over.window === held.first && over.button == held.second) {
                when (held.second) {
                    TitleButton.CLOSE -> held.first.close()
                    TitleButton.MAXIMIZE -> desktop.setMaximized(held.first, !held.first.maximized)
                    TitleButton.MINIMIZE -> desktop.minimize(held.first)
                }
            }
            desktop.requestRedraw()
            return true
        }
        if (button != BTN_LEFT) return at(desktop.pointerX, desktop.pointerY) != null
        when (val hit = at(desktop.pointerX, desktop.pointerY) ?: return false) {
            is Decoration.Bar -> {
                desktop.focus(hit.window)
                val tapped = hit.button
                if (tapped != null) {
                    this.pressed = hit.window to tapped
                } else {
                    val now = System.nanoTime()
                    if (now - lastBarClick < desktop.doubleClickMillis * 1_000_000) {
                        desktop.setMaximized(hit.window, !hit.window.maximized)
                        lastBarClick = 0
                    } else {
                        lastBarClick = now
                        desktop.beginMove(hit.window)
                    }
                }
            }
            is Decoration.Edge -> {
                desktop.focus(hit.window)
                desktop.beginResize(hit.window, hit.edges)
            }
        }
        desktop.requestRedraw()
        return true
    }

    /** The topmost title bar or edge at a point, if no program's window is above it there. */
    fun at(x: Double, y: Double): Decoration? {
        if (desktop.locked || desktop.inOverview) return null
        val (fullscreen, normal) = desktop.stackingOrder().partition { it.fullscreen }
        if (fullscreen.any { !desktop.directed(it) && desktop.windowHit(it, x, y) != null }) return null
        // A director's windows are where it draws them, with no bars or edges of ours.
        for ((window, place) in desktop.places.entries.reversed()) if (window.mapped && place.takesInput && place.alpha > 0f && desktop.directedHit(window, place, x, y) != null) return null
        for (window in normal.asReversed()) {
            if (desktop.directed(window)) continue
            if (desktop.windowHit(window, x, y) != null) return null // the window's own picture is on top
            barAt(window, x, y)?.let { return it }
        }
        return null
    }

    /** [window] is gone: its title bar's picture too. */
    fun forget(window: Window) {
        textures.remove(window)?.second?.close()
        if (hover?.first === window) hover = null
        if (pressed?.first === window) pressed = null
    }

    /** Lets go of every title bar's picture (drawn again when next needed). */
    override fun close() {
        for ((_, cached) in textures) cached.second.close()
        textures.clear()
    }
}

// The left mouse button (a Linux code).
private const val BTN_LEFT = 0x110
