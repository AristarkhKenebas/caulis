package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.Color
import dev.caulis.render.GpuTarget

/**
 * Pictures of the desktop for screenshots and screen sharing: what is shown (windows hidden from
 * capture as black boxes), and what changed since a recording last got a picture.
 */
internal class DesktopCapture(private val desktop: Desktop) {

    // Views of screen recordings: what each has seen, so it gets only what changed since.
    internal val captureViews = ArrayList<DesktopView>()

    internal val commitListeners = ArrayList<(Surface) -> Unit>()

    internal val frameListeners = ArrayList<() -> Unit>()

    /** A record of what a recording of [monitor] last got (see [captureChanges]). Remove it with [removeCaptureView]. */
    internal fun createCaptureView(monitor: Monitor): DesktopView = DesktopView(monitor).also { captureViews += it }

    internal fun removeCaptureView(view: DesktopView) {
        captureViews -= view
    }

    /**
     * What changed on the desktop since [view] last got a picture (everything the first
     * time), or null if nothing did. Counts as given: the next call starts from now.
     */
    internal fun captureChanges(view: DesktopView, cursor: Boolean): Rect? {
        desktop.compare(view, captureItems(cursor))
        return view.takeRepaint(1, view.monitor.area)
    }

    // Everything on screen, with or without our cursor; windows hidden from capture are
    // black boxes (their popups too).
    internal fun captureItems(cursor: Boolean, hide: Boolean = true): List<DrawItem> {
        val all = desktop.items()
        val shown = if (cursor) all else all.subList(0, desktop.itemsCursorStart.coerceAtMost(all.size))
        if (!hide) return shown
        if (desktop.allWindows.none { desktop.behaviorOf(it).hiddenFromCapture } && desktop.layerSurfaces.none { desktop.layerBehaviorOf(it).hiddenFromCapture }) return shown
        return shown.map { item ->
            // Whose picture it is: a window's (or panel's) own, its title bar, its picture
            // flying or appearing in an animation.
            val owner = when {
                item is DrawItem.Picture -> ownerOf(item.surface)
                item.key is EffectKey -> (item.key as EffectKey).owner
                item.key is Desktop.Flight -> (item.key as Desktop.Flight).window
                else -> null
            }
            val hidden = when (owner) {
                is Window -> desktop.behaviorOf(owner).hiddenFromCapture
                is LayerSurface -> desktop.layerBehaviorOf(owner).hiddenFromCapture
                else -> false
            }
            if (hidden && (item is DrawItem.Picture || item is DrawItem.Image || item is DrawItem.Effect)) {
                DrawItem.Color(EffectKey("hidden from capture", item.key), item.rect, 0f, 0f, 0f, item.covers).also { it.only = item.only }
            } else {
                item
            }
        }
    }

    // The window or panel [surface] belongs to (it, a part of it, or one of its menus), if any.
    internal fun ownerOf(surface: Surface): Any? {
        var current = surface
        repeat(16) {
            while (true) current = current.subsurface?.parent ?: break
            when (val role = current.roleObject) {
                is Window, is LayerSurface -> return role
                is Popup -> current = role.parent?.surface ?: role.layerParent?.surface ?: return null
                else -> return null
            }
        }
        return null
    }

    fun capture(target: GpuTarget, cursor: Boolean, monitor: Monitor = desktop.monitorList.first()) {
        val items = captureItems(cursor)
        desktop.renderer.begin(target, desktop.backgroundColor.red, desktop.backgroundColor.green, desktop.backgroundColor.blue)
        desktop.draw(items, monitor.area, monitor::toPixels, monitor.scale, monitor)
        desktop.renderer.end()
    }

    fun captureWindow(window: Window, target: GpuTarget) {
        val visible = window.visibleRect
        val items = ArrayList<DrawItem>()
        if (desktop.behaviorOf(window).hiddenFromCapture) {
            items += DrawItem.Color(EffectKey("hidden from capture", window), Rect(0, 0, visible.width, visible.height), 0f, 0f, 0f)
        } else {
            desktop.addTree(items, window.surface, -visible.x, -visible.y)
        }
        val scale = desktop.monitorOf(window).scale
        desktop.renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
        desktop.draw(items, null, { rect -> pixelsOf(rect, scale) }, scale)
        desktop.renderer.end()
    }

    fun windowPixelSize(window: Window): Pair<Int, Int> {
        val visible = window.visibleRect
        val scale = desktop.monitorOf(window).scale
        return Math.round(visible.width * scale).toInt().coerceAtLeast(1) to Math.round(visible.height * scale).toInt().coerceAtLeast(1)
    }

    // A rectangle in units (from 0, 0) as pixels of [scale].
    internal fun pixelsOf(rect: Rect, scale: Double): Rect {
        fun p(v: Int) = Math.round(v * scale).toInt()
        return Rect(p(rect.x), p(rect.y), p(rect.right) - p(rect.x), p(rect.bottom) - p(rect.y))
    }
}
