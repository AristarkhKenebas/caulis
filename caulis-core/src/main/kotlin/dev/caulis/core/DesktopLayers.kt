package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Panels, docks, bars and wallpapers (layer shell): where they go, the room they keep free for
 * themselves, and who of them has the keyboard.
 */
internal class DesktopLayers(private val desktop: Desktop) {

    internal val layerSurfaces = ArrayList<LayerSurface>()

    // The monitor of each panel that did not ask for one (the one with the focus when it came).
    internal val layerMonitors = HashMap<LayerSurface, Monitor>()

    // The monitor [layer] is on, without placing it anywhere (null: on none now).
    internal fun monitorOfLayer(layer: LayerSurface): Monitor? =
        layer.output?.let { output -> desktop.monitorList.firstOrNull { it.output === output } } ?: layerMonitors[layer]

    // The panel with the keyboard (a launcher, or one clicked that wants it), if any.
    internal var layerFocus: LayerSurface? = null

    fun layerCommitted(layer: LayerSurface) {
        if (layer !in layerSurfaces) {
            layerSurfaces += layer
            desktop.layersLog.info { "Panel \"${layer.namespace}\" (${layer.surface.resource.client.name}) on the ${layer.current.layer} layer of ${desktop.monitorOf(layer).name}, anchors ${layer.current.anchor}, exclusive zone ${layer.current.exclusiveZone}" }
        }
        arrangeLayers(desktop.monitorOf(layer))
        updateLayerKeyboard()
        desktop.changed()
    }

    fun layerUnmapped(layer: LayerSurface) {
        desktop.layersLog.info { "Panel \"${layer.namespace}\" is gone" }
        val monitor = desktop.monitorOf(layer)
        layerSurfaces -= layer
        layerMonitors -= layer
        desktop.popupHost.popups.filter { it.layerParent === layer }.forEach { it.dismiss() }
        if (layerFocus === layer) {
            // It had the keyboard: back to the window (or the next launcher that wants it).
            layerFocus = null
            desktop.widgetHost.keyboardTo(desktop.focusedWindow?.surface)
        }
        arrangeLayers(monitor)
        updateLayerKeyboard()
        desktop.changed()
    }

    // Places the layer surfaces of [monitor], the way wlroots-based compositors do: first
    // those that reserve space (each takes it from its edge), then the others in what is left.
    internal fun arrangeLayers(monitor: Monitor, refit: Boolean = true) {
        // Banners of our own notices take their room first (from panels too, or only from windows).
        val banner = desktop.noticeBoard.style.placement as? NoticePlacement.Banner
        fun room(kind: NoticePlacement.Banner.Room) = if (banner == null) 0 else desktop.noticeBoard.bannerRoom(monitor, desktop.monitorList, desktop.noticeFallback(monitor), kind)
        val pushAll = room(NoticePlacement.Banner.Room.PUSH_ALL)
        val pushWindows = room(NoticePlacement.Banner.Room.PUSH_WINDOWS)
        val bottom = banner?.bottom == true
        val full = desktop.withoutBanners(monitor.area, monitor, pushAll, bottom)
        // Widgets keeping room at an edge (a bar of our own), nearest it in the order added:
        // panels and windows make way.
        var free = full
        for (widget in desktop.widgetHost.widgets) {
            val reserve = userCode("Your widget (reserve)", null) { widget.reserve(monitor) } ?: continue
            if (reserve.size <= 0) continue
            free = when (reserve.edge) {
                Edge.TOP -> Rect(free.x, free.y + reserve.size, free.width, (free.height - reserve.size).coerceAtLeast(1))
                Edge.BOTTOM -> Rect(free.x, free.y, free.width, (free.height - reserve.size).coerceAtLeast(1))
                Edge.LEFT -> Rect(free.x + reserve.size, free.y, (free.width - reserve.size).coerceAtLeast(1), free.height)
                Edge.RIGHT -> Rect(free.x, free.y, (free.width - reserve.size).coerceAtLeast(1), free.height)
            }
        }
        var usable = free
        val ordered = layerSurfaces.filter { desktop.monitorOf(it) === monitor }.sortedByDescending { it.current.layer } // overlay first
        for (layer in ordered.filter { it.current.exclusiveZone > 0 }) {
            placeLayer(layer, free)
            usable = reserve(usable, layer)
        }
        for (layer in ordered.filter { it.current.exclusiveZone <= 0 }) {
            placeLayer(layer, if (layer.current.exclusiveZone < 0) full else usable)
        }
        // (Below the ones that push everything: those are nearest the edge.)
        if (pushWindows > 0) usable = desktop.withoutBanners(usable, monitor, pushAll + pushWindows, bottom)
        if (usable != monitor.usableArea) {
            monitor.usableArea = usable
            if (refit) desktop.screens.refitWindows(monitor)
        }
    }

    private fun placeLayer(layer: LayerSurface, bounds: Rect) {
        val s = layer.current
        val anchor = s.anchor
        fun has(edge: Int) = anchor and edge != 0
        val width = if (s.width == 0) bounds.width - s.marginLeft - s.marginRight else s.width
        val height = if (s.height == 0) bounds.height - s.marginTop - s.marginBottom else s.height
        val x = when {
            has(ANCHOR_LEFT) && !has(ANCHOR_RIGHT) -> bounds.x + s.marginLeft
            has(ANCHOR_RIGHT) && !has(ANCHOR_LEFT) -> bounds.right - width - s.marginRight
            else -> bounds.x + s.marginLeft + (bounds.width - s.marginLeft - s.marginRight - width) / 2
        }
        val y = when {
            has(ANCHOR_TOP) && !has(ANCHOR_BOTTOM) -> bounds.y + s.marginTop
            has(ANCHOR_BOTTOM) && !has(ANCHOR_TOP) -> bounds.bottom - height - s.marginBottom
            else -> bounds.y + s.marginTop + (bounds.height - s.marginTop - s.marginBottom - height) / 2
        }
        layer.configure(width.coerceAtLeast(1), height.coerceAtLeast(1))
        layer.x = x
        layer.y = y
    }

    // The area left after [layer] took its exclusive zone from its edge.
    private fun reserve(area: Rect, layer: LayerSurface): Rect {
        val s = layer.current
        val edge = when {
            s.exclusiveEdge != 0 -> s.exclusiveEdge
            // Anchored to one edge, or to one edge and both of its neighbours (a bar).
            s.anchor in setOf(ANCHOR_TOP, ANCHOR_TOP or ANCHOR_LEFT or ANCHOR_RIGHT) -> ANCHOR_TOP
            s.anchor in setOf(ANCHOR_BOTTOM, ANCHOR_BOTTOM or ANCHOR_LEFT or ANCHOR_RIGHT) -> ANCHOR_BOTTOM
            s.anchor in setOf(ANCHOR_LEFT, ANCHOR_LEFT or ANCHOR_TOP or ANCHOR_BOTTOM) -> ANCHOR_LEFT
            s.anchor in setOf(ANCHOR_RIGHT, ANCHOR_RIGHT or ANCHOR_TOP or ANCHOR_BOTTOM) -> ANCHOR_RIGHT
            else -> return area // anchored to opposite edges or a corner: reserves nothing
        }
        return when (edge) {
            ANCHOR_TOP -> (s.exclusiveZone + s.marginTop).let { Rect(area.x, area.y + it, area.width, (area.height - it).coerceAtLeast(1)) }
            ANCHOR_BOTTOM -> (s.exclusiveZone + s.marginBottom).let { Rect(area.x, area.y, area.width, (area.height - it).coerceAtLeast(1)) }
            ANCHOR_LEFT -> (s.exclusiveZone + s.marginLeft).let { Rect(area.x + it, area.y, (area.width - it).coerceAtLeast(1), area.height) }
            else -> (s.exclusiveZone + s.marginRight).let { Rect(area.x, area.y, (area.width - it).coerceAtLeast(1), area.height) }
        }
    }

    // A launcher that wants the keyboard (top or overlay layer) gets it and keeps it while open.
    internal fun exclusiveLayer(): LayerSurface? = layerSurfaces.lastOrNull {
        it.mapped && it.current.keyboard == KEYBOARD_EXCLUSIVE && it.current.layer >= LayerShell.Layer.TOP
    }

    private fun updateLayerKeyboard() {
        val exclusive = exclusiveLayer()
        when {
            exclusive != null -> if (layerFocus !== exclusive) {
                layerFocus = exclusive
                desktop.widgetHost.keyboardTo(exclusive.surface)
            }
            layerFocus?.let { !it.mapped || it.current.keyboard == KEYBOARD_NONE } == true -> {
                // The panel that had the keyboard is gone or does not want it: back to the window.
                layerFocus = null
                desktop.widgetHost.keyboardTo(desktop.focusedWindow?.surface)
            }
        }
    }

    internal fun layerBehaviorOf(layer: LayerSurface): LayerBehavior = try {
        desktop.layerBehaviorFor(layer)
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        desktop.log.error("Your panel rule failed for ${layer.namespace} (ignored): $e", e)
        LayerBehavior.USUAL
    }
}
