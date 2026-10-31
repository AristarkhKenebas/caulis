package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTarget

/**
 * The cursor on the screens' cursor planes: put there before each frame, moved with the
 * pointer at once, and from the input thread before this one has caught up.
 */
internal class DesktopCursorPlanes(private val desktop: Desktop) {

    // Puts the cursor on [view]'s cursor plane if it can be there (on this monitor, fitting the
    // plane, the monitor not turned); else back into the frames. Called before each frame.
    internal fun placeHardwareCursor(view: DesktopView) {
        view.planeCursorOffset = null
        val plane = view.hardwareCursor
        if (plane == null) {
            view.cursorOnPlane = false
            return
        }
        val monitor = view.monitor
        val all = desktop.items()
        val cursor = all.subList(desktop.itemsCursorStart.coerceAtMost(all.size), all.size)
        fun intoFrames() {
            if (view.cursorOnPlane || view.planeCursorKey != null) plane.hide()
            view.cursorOnPlane = false
            view.planeCursorKey = null
        }
        if (cursor.isEmpty()) {
            // No cursor at all (hidden): nothing on the plane, nothing in the frames.
            if (view.planeCursorKey != null) plane.hide()
            view.planeCursorKey = null
            view.cursorOnPlane = true
            return
        }
        if (monitor.transform != Transform.NORMAL || desktop.lockHidesDesktop) return intoFrames()
        val bounds = cursor.map { it.rect }.reduce(Rect::union)
        if (bounds.intersect(monitor.area) == null) {
            // On another monitor: none here.
            if (view.planeCursorKey != null) plane.hide()
            view.planeCursorKey = null
            view.cursorOnPlane = true
            return
        }
        // The cursor where the pointer really is, to the monitor's pixel: at scale 2 it moved
        // in steps of 2 pixels (one unit) before.
        val whole = monitor.toPixels(bounds)
        val dx = Math.round((desktop.pointerX - Math.floor(desktop.pointerX)) * monitor.scale).toInt()
        val dy = Math.round((desktop.pointerY - Math.floor(desktop.pointerY)) * monitor.scale).toInt()
        val pixels = Rect(whole.x + dx, whole.y + dy, whole.width, whole.height)
        if (pixels.width > plane.width || pixels.height > plane.height) return intoFrames()
        // A new picture when the cursor looks different (another shape, its next frame, the
        // program drew a new one, another size or scale); else it only moves.
        val key = listOf(cursor.map { item -> (item as? DrawItem.Picture)?.surface ?: (item as? DrawItem.Image)?.texture ?: item.key }, pixels.width, pixels.height, monitor.scale,
            desktop.cursors.cursorVersion, if (cursor.any { it is DrawItem.Picture }) desktop.version else 0L)
        val draw: ((GpuTarget) -> Unit)? = if (key == view.planeCursorKey) null else { target ->
            desktop.renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
            desktop.draw(cursor, null, { r -> monitor.toPixels(r).let { Rect(it.x - whole.x, it.y - whole.y, it.width, it.height) } }, monitor.scale, monitor)
            desktop.renderer.end()
        }
        if (!plane.show(pixels.x, pixels.y, draw)) return intoFrames()
        view.planeCursorKey = key
        view.cursorOnPlane = true
        view.planeCursorOffset = Rect(bounds.x - Math.floor(desktop.pointerX).toInt(), bounds.y - Math.floor(desktop.pointerY).toInt(), bounds.width, bounds.height)
    }

    // Movements the input thread read that this thread has not handled yet.
    internal val earlyInFlight = java.util.concurrent.atomic.AtomicInteger()

    // What the input thread needs, as this thread last saw it (null: it moves nothing).
    private class EarlyPlane(val area: Rect, val scale: Double, val plane: MovableFromAnyThread, val offset: Rect)

    private class EarlyState(val x: Double, val y: Double, val areas: List<Rect>, val planes: List<EarlyPlane>)

    @Volatile
    private var earlyState: EarlyState? = null

    // (Only once an input thread uses it: nothing made for each movement otherwise.)
    @Volatile
    private var earlyUsed = false

    internal fun publishEarly() {
        if (!earlyUsed) return
        earlyState = if (!desktop.earlyCursorMoves || !desktop.quickCursorMoves || desktop.monitorEdges != MonitorEdges.TOUCHING || desktop.monitorList.isEmpty() || desktop.constraintNow() != null) {
            null
        } else {
            EarlyState(
                desktop.pointerX, desktop.pointerY, desktop.monitorList.map { it.area },
                desktop.views.mapNotNull { view ->
                    val offset = view.planeCursorOffset ?: return@mapNotNull null
                    val plane = view.hardwareCursor as? MovableFromAnyThread ?: return@mapNotNull null
                    if (!view.cursorOnPlane || view.planeCursorKey == null || view.monitor.transform != Transform.NORMAL) return@mapNotNull null
                    EarlyPlane(view.monitor.area, view.monitor.scale, plane, offset)
                },
            )
        }
    }

    internal inner class Early : dev.caulis.core.input.EarlyPointer {
        // (The input thread's own: where it thinks the pointer is, and where it showed it.)
        private var x = Double.NaN
        private var y = Double.NaN
        private var shownX = Double.NaN
        private var shownY = Double.NaN

        override fun movedEarly(dx: Double, dy: Double) {
            // All before this one handled here: start from where this thread says it is.
            val before = earlyInFlight.getAndIncrement()
            earlyUsed = true
            val state = earlyState
            if (state == null) {
                x = Double.NaN
                return
            }
            if (before == 0 || x.isNaN()) {
                x = state.x
                y = state.y
            }
            val (nx, ny) = movedOver(state.areas, x, y, dx, dy)
            x = nx
            y = ny
        }

        override fun showEarly() {
            val state = earlyState ?: return
            if (x.isNaN() || (x == shownX && y == shownY)) return
            shownX = x
            shownY = y
            val fx = Math.floor(x)
            val fy = Math.floor(y)
            for (p in state.planes) {
                // (As quickCursor does it.)
                val bounds = Rect(fx.toInt() + p.offset.x, fy.toInt() + p.offset.y, p.offset.width, p.offset.height)
                if (bounds.intersect(p.area) == null) continue
                val left = Math.round((bounds.x - p.area.x) * p.scale).toInt()
                val top = Math.round((bounds.y - p.area.y) * p.scale).toInt()
                val dx = Math.round((x - fx) * p.scale).toInt()
                val dy = Math.round((y - fy) * p.scale).toInt()
                if (p.plane.moveFromAnyThread(left + dx, top + dy)) desktop.inputLog.trace { "Cursor moved from the input thread to ${left + dx}, ${top + dy}" }
            }
        }

        override fun caughtUp() = caughtUp(1)

        override fun caughtUp(count: Int) {
            publishEarly()
            // The last one read so far: the cursor where this thread says (the same place
            // unless the movement was taken — a gesture — or kept: then it goes back).
            if (earlyInFlight.get() <= count && desktop.quickCursorMoves) quickCursor()
            earlyInFlight.addAndGet(-count)
        }
    }

    // Moves the cursor plane with the pointer. Run for every movement: it makes nothing (no
    // rectangles, no iterator), all in plain numbers — as Monitor.toPixels would round them.
    internal fun quickCursor() {
        for (i in desktop.views.indices) {
            val view = desktop.views[i]
            val offset = view.planeCursorOffset ?: continue
            val plane = view.hardwareCursor ?: continue
            if (!view.cursorOnPlane || view.planeCursorKey == null) continue
            val monitor = view.monitor
            val area = monitor.area
            val fx = Math.floor(desktop.pointerX)
            val fy = Math.floor(desktop.pointerY)
            // The cursor's picture, in desktop units: off this monitor, not shown here.
            val left = fx.toInt() + offset.x
            val top = fy.toInt() + offset.y
            val right = left + offset.width
            val bottom = top + offset.height
            if (minOf(right, area.right) <= maxOf(left, area.x) || minOf(bottom, area.bottom) <= maxOf(top, area.y)) continue
            // In the monitor's pixels.
            val pxLeft = monitor.px(left - area.x)
            val pxTop = monitor.px(top - area.y)
            if (monitor.px(right - area.x) - pxLeft > plane.width || monitor.px(bottom - area.y) - pxTop > plane.height) continue
            val dx = Math.round((desktop.pointerX - fx) * monitor.scale).toInt()
            val dy = Math.round((desktop.pointerY - fy) * monitor.scale).toInt()
            plane.show(pxLeft + dx, pxTop + dy, null)
        }
    }
}
