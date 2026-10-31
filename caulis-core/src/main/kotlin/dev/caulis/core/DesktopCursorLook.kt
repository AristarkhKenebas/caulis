package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.Color
import dev.caulis.render.GpuTexture
import kotlin.math.roundToInt

/**
 * The pointer's looks as the desktop draws it: which shape, its pictures and their frames,
 * hiding it while typing or idle. Lives as long as its desktop.
 */
internal class DesktopCursorLook(private val desktop: Desktop) {

    // Our own cursor over a window's edge (resize arrows), or null.
    internal var decorationCursor: String? = null

    internal fun addCursor(items: MutableList<DrawItem>) {
        // (Whole units here, rounded down — also left of and above 0; the cursor plane adds
        // the part of a unit in the monitor's pixels, see placeHardwareCursor.)
        val x = Math.floor(desktop.pointerX).toInt()
        val y = Math.floor(desktop.pointerY).toInt()
        // What is dragged (drag and drop), under the cursor.
        desktop.seat.drag?.let { drag -> drag.icon?.takeIf { it.hasContent && it.resource.isAlive }?.let { desktop.sceneBuilder.addTree(items, it, x + drag.iconX, y + drag.iconY) } }
        if (cursorHidden) return
        val shape = cursorShapeNow()
        if (shape == null) {
            // The program drew its own cursor (or hid it).
            val own = desktop.seat.cursorSurface
            if (own != null && own.hasContent) desktop.sceneBuilder.addTree(items, own, x - desktop.seat.cursorHotspotX, y - desktop.seat.cursorHotspotY)
            return
        }
        if (shape != shownShape) {
            desktop.cursorLog.trace { "Cursor: $shape" }
            shownShape = shape
            shapeSince = System.nanoTime()
        }
        // Loaded in the size it has on screen (the monitor it is on), so it is sharp at any scale.
        val pixels = Math.round(desktop.cursorTheme.size * desktop.monitorAt(desktop.pointerX, desktop.pointerY).scale).toInt()
        val cursor = desktop.cursorTheme.cursor(shape, pixels) ?: desktop.cursorTheme.cursor("default", pixels)
        if (cursor == null) {
            // Only if even Caulis's own cursors could not be read (a broken jar): a plain square.
            items += DrawItem.Color(CURSOR_BORDER, Rect(x, y, 12, 12), 0f, 0f, 0f)
            items += DrawItem.Color(CURSOR_FILL, Rect(x + 1, y + 1, 10, 10), 1f, 1f, 1f)
            return
        }
        val (image, untilNext) = cursor.frameAt((System.nanoTime() - shapeSince) / 1_000_000)
        // Themes have a few sizes; the nearest one is stretched to the size wanted.
        val scale = desktop.cursorTheme.size.toDouble() / image.nominalSize
        val rect = Rect(
            x - (image.hotspotX * scale).roundToInt(), y - (image.hotspotY * scale).roundToInt(),
            (image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1),
        )
        items += DrawItem.Image(CURSOR_IMAGE, cursorTexture(image), rect)
        if (cursor.animated) nextCursorFrameIn(untilNext)
    }

    // Changes only the cursor makes (a hidden cursor, the next frame of an animated one).
    internal var cursorVersion = 0L

    internal var cursorHidden = false

    internal val cursorTextures = HashMap<dev.caulis.core.cursor.CursorImage, GpuTexture>()

    private var shownShape: String? = null

    private var shapeSince = 0L

    private var cursorFrameTimer: EventLoop.Timer? = null

    private var cursorIdleTimer: EventLoop.Timer? = null

    internal fun cursorChanged() {
        cursorVersion++
        desktop.onChange()
    }

    // The cursor the window manager shows: its own while it moves or resizes a window or
    // nothing is under the pointer, else what the program there asked for (null = the
    // program's own picture).
    internal fun cursorShapeNow(): String? {
        val current = desktop.grab
        return when {
            current is DesktopPointerGrab.Grab.Move -> "grabbing"
            current is DesktopPointerGrab.Grab.Resize -> resizeCursor(current.edges)
            desktop.overlay != null && !desktop.locked -> desktop.overlay?.cursor ?: "default"
            (desktop.widgetHost.pressedWidget != null || desktop.widgetHost.hoveredWidget != null) && !desktop.inOverview -> userCode("Your widget (cursor)", "default") { (desktop.widgetHost.pressedWidget?.widget ?: desktop.widgetHost.hoveredWidget)!!.cursor }
            desktop.inOverview -> "default"
            desktop.noticeBoard.hovering -> "default" // our notice, not the program below it
            decorationCursor != null -> decorationCursor
            desktop.seat.pointerFocus == null && desktop.seat.penFocus == null -> "default"
            else -> desktop.seat.cursorShape
        }
    }

    internal fun resizeCursor(edges: Int): String = when (edges) {
        XdgToplevel.ResizeEdge.TOP -> "n-resize"
        XdgToplevel.ResizeEdge.BOTTOM -> "s-resize"
        XdgToplevel.ResizeEdge.LEFT -> "w-resize"
        XdgToplevel.ResizeEdge.RIGHT -> "e-resize"
        XdgToplevel.ResizeEdge.TOP_LEFT -> "nw-resize"
        XdgToplevel.ResizeEdge.TOP_RIGHT -> "ne-resize"
        XdgToplevel.ResizeEdge.BOTTOM_LEFT -> "sw-resize"
        XdgToplevel.ResizeEdge.BOTTOM_RIGHT -> "se-resize"
        else -> "all-resize"
    }

    // Each cursor picture goes to the GPU once.
    private fun cursorTexture(image: dev.caulis.core.cursor.CursorImage): GpuTexture = cursorTextures.getOrPut(image) {
        desktop.renderer.createTexture(image.width, image.height).also { texture ->
            java.lang.foreign.Arena.ofConfined().use { arena ->
                // Premultiplied 0xAARRGGBB ints are the BGRA bytes the texture wants.
                texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *image.pixels), 0, image.height)
            }
        }
    }

    private fun nextCursorFrameIn(millis: Long) {
        if (cursorFrameTimer != null) return
        cursorFrameTimer = desktop.compositor.display.loop.after(millis.coerceIn(1, 1000)) {
            cursorFrameTimer = null
            cursorChanged()
        }
    }

    // The mouse was used: the cursor shows again, and the idle countdown starts over.
    internal fun cursorActive() {
        cursorIdleTimer?.cancel()
        cursorIdleTimer = null
        if (cursorHidden) {
            cursorHidden = false
            cursorChanged()
        }
        if (desktop.hideCursorAfterMillis > 0) {
            cursorIdleTimer = desktop.compositor.display.loop.after(desktop.hideCursorAfterMillis) {
                cursorIdleTimer = null
                hideCursor()
            }
        }
    }

    internal fun hideCursor() {
        if (cursorHidden) return
        cursorHidden = true
        cursorChanged()
    }
}
