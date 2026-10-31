package dev.caulis.core

/**
 * What is at a point of the desktop: which surface or widget the pointer, a finger or a pen
 * meets there, and where in it.
 */
internal class DesktopHitTest(private val desktop: Desktop) {

    internal fun surfaceAt(x: Double, y: Double): Desktop.Hit? = (pointedAt(x, y, widgetsToo = false) as? Desktop.Pointed.AtSurface)?.hit

    internal fun pointedAt(x: Double, y: Double, widgetsToo: Boolean): Desktop.Pointed? {
        fun Desktop.Hit?.at(): Desktop.Pointed? = this?.let(Desktop.Pointed::AtSurface)
        fun widget(level: DrawingLevel): Desktop.Pointed? =
            if (widgetsToo && desktop.widgetHost.widgets.isNotEmpty()) desktop.widgetHost.widgetAt(x, y, level)?.let { (widget, area) -> Desktop.Pointed.AtWidget(widget, area, level) } else null
        if (desktop.lockHost.lockHidesDesktop) {
            // Only the lock is there: the widgets usable on it, the locker's surfaces (each on its monitor).
            for (level in listOf(DrawingLevel.ON_TOP, DrawingLevel.ABOVE_WINDOWS, DrawingLevel.BELOW_WINDOWS, DrawingLevel.WALLPAPER)) widget(level)?.let { return it }
            for (lockSurface in desktop.lockHost.lockSurfaces()) {
                val area = desktop.screens.lockMonitor(lockSurface).area
                hitTree(lockSurface.surface, x - area.x, y - area.y, lockSurface.surface)?.let { return it.at() }
            }
            return null
        }
        if (desktop.locked) {
            // Behind our own lock screen (the desktop drawn): what is there, if the lock lets it have input.
            return when (val there = pointedAtUnlocked(x, y, widgetsToo)) {
                is Desktop.Pointed.AtSurface -> there.takeIf { desktop.lockHost.lockAllows(it.hit.root) }
                else -> there // (only usable widgets are found)
            }
        }
        return pointedAtUnlocked(x, y, widgetsToo)
    }

    private fun pointedAtUnlocked(x: Double, y: Double, widgetsToo: Boolean): Desktop.Pointed? {
        fun Desktop.Hit?.at(): Desktop.Pointed? = this?.let(Desktop.Pointed::AtSurface)
        fun widget(level: DrawingLevel): Desktop.Pointed? =
            if (widgetsToo && desktop.widgetHost.widgets.isNotEmpty()) desktop.widgetHost.widgetAt(x, y, level)?.let { (widget, area) -> Desktop.Pointed.AtWidget(widget, area, level) } else null
        widget(DrawingLevel.ON_TOP)?.let { return it }
        for ((surface, place) in desktop.overlayHost.unmanaged.entries.reversed()) {
            hitTree(surface, x - place.first, y - place.second, surface)?.let { return it.at() }
        }
        for (popup in desktop.popupHost.popups.asReversed()) {
            if (!desktop.popupHost.popupShown(popup)) continue
            val (px, py) = popupOrigin(popup)
            hitTree(popup.surface, x - px, y - py, popup.surface)?.let { return it.at() }
        }
        layerAt(LayerShell.Layer.OVERLAY, x, y)?.let { return it.at() }
        for (pad in desktop.pads.shownPads.asReversed()) {
            if (pad.leaving) continue
            for (window in pad.windows.asReversed()) {
                val visible = window.visibleRect
                hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)?.let { return it.at() }
            }
        }
        val (fullscreen, normal) = desktop.sceneBuilder.stackingOrder().partition { it.fullscreen }
        for (window in fullscreen.asReversed()) if (window !in desktop.places && !desktop.directed(window)) windowHit(window, x, y)?.let { return it.at() }
        layerAt(LayerShell.Layer.TOP, x, y)?.let { return it.at() }
        widget(DrawingLevel.ABOVE_WINDOWS)?.let { return it }
        // The director's windows, topmost first, where they are drawn (stretched maybe).
        for ((window, place) in desktop.places.entries.reversed()) if (window.mapped && place.takesInput && place.alpha > 0f) directedHit(window, place, x, y)?.let { return it.at() }
        for (window in normal.asReversed()) if (window !in desktop.places && !desktop.directed(window)) windowHit(window, x, y)?.let { return it.at() }
        widget(DrawingLevel.BELOW_WINDOWS)?.let { return it }
        layerAt(LayerShell.Layer.BOTTOM, x, y)?.let { return it.at() }
        layerAt(LayerShell.Layer.BACKGROUND, x, y)?.let { return it.at() }
        return widget(DrawingLevel.WALLPAPER)
    }

    // [place] is where the picture is; the program is told the point in its own size.
    internal fun directedHit(window: Window, place: WindowPlace, x: Double, y: Double): Desktop.Hit? {
        if (x < place.x || y < place.y || x >= place.x + place.width || y >= place.y + place.height) return null
        place.clip?.let { c -> if (x < c.x || y < c.y || x >= c.right || y >= c.bottom) return null }
        val visible = window.visibleRect
        val scaleX = if (visible.width > 0 && place.width > 0) place.width / visible.width else 1.0
        val scaleY = if (visible.height > 0 && place.height > 0) place.height / visible.height else 1.0
        val hit = hitTree(window.surface, visible.x + (x - place.x) / scaleX, visible.y + (y - place.y) / scaleY, window.surface) ?: return null
        return Desktop.Hit(hit.surface, hit.localX, hit.localY, hit.root, scaleX, scaleY)
    }

    internal fun windowHit(window: Window, x: Double, y: Double): Desktop.Hit? {
        val visible = window.visibleRect
        return hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)
    }

    private fun layerAt(layer: LayerShell.Layer, x: Double, y: Double): Desktop.Hit? {
        for (surface in desktop.layerHost.layerSurfaces.asReversed()) {
            if (!surface.mapped || surface.current.layer != layer) continue
            hitTree(surface.surface, x - surface.x, y - surface.y, surface.surface)?.let { return it }
        }
        return null
    }

    // Checks a surface and its subsurfaces, topmost first. x, y are relative to [surface].
    private fun hitTree(surface: Surface, x: Double, y: Double, root: Surface): Desktop.Hit? {
        for (child in surface.stack.asReversed()) {
            if (child === surface) {
                if (surface.hasContent && surface.acceptsInput(x, y)) return Desktop.Hit(surface, x, y, root)
            } else {
                val sub = child.subsurface ?: continue
                hitTree(child, x - sub.x, y - sub.y, root)?.let { return it }
            }
        }
        return null
    }

    // Where a popup's surface starts on the output.
    internal fun popupOrigin(popup: Popup): Pair<Int, Int> {
        val (parentX, parentY) = popup.parent?.let(::visibleOrigin) ?: popup.layerParent?.let { it.x to it.y } ?: (0 to 0)
        val visible = popup.shell.visibleRect
        return (parentX + popup.x - visible.x) to (parentY + popup.y - visible.y)
    }

    // Where the visible top left corner of a window or popup is on the output.
    internal fun visibleOrigin(shell: ShellSurface): Pair<Int, Int> = when (val role = shell.role) {
        is Window -> role.x to role.y
        is Popup -> {
            val (parentX, parentY) = role.parent?.let(::visibleOrigin) ?: role.layerParent?.let { it.x to it.y } ?: (0 to 0)
            (parentX + role.x) to (parentY + role.y)
        }
        else -> 0 to 0
    }
}
