package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Widgets and drawings of your own: drawn at their levels, hovered, pressed, given the keyboard;
 * the keyboard's way to widgets and surfaces.
 */
internal class DesktopWidgets(private val desktop: Desktop) {

    internal val drawings = java.util.EnumMap<DrawingLevel, MutableList<Drawing>>(DrawingLevel::class.java)

    fun addDrawing(level: DrawingLevel, drawing: Drawing): AutoCloseable {
        drawings.getOrPut(level) { ArrayList() } += drawing
        desktop.changed()
        return AutoCloseable {
            drawings[level]?.remove(drawing)
            desktop.changed()
        }
    }

    // The drawings of [level], on every monitor.
    internal fun addDrawings(all: MutableList<DrawItem>, level: DrawingLevel, withWidgets: Boolean = true) {
        val list = drawings[level].orEmpty()
        if (list.isEmpty()) {
            if (withWidgets) addWidgets(all, level)
            return
        }
        val seconds = ((System.nanoTime() - desktop.paintStart) / 1e9).toFloat()
        val scene = wallpaperScene()
        for (monitor in desktop.monitorList) {
            for (drawing in list.toList()) {
                val canvas = OverlayCanvas(monitor, listOf("drawing", drawing, monitor.name), seconds, scene)
                try {
                    drawing.draw(monitor, canvas)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    desktop.log.error("Your drawing $drawing failed (it is left out)", e)
                    continue
                }
                if (canvas.movingPaint) desktop.paintsMoving = true
                all += canvas.items
            }
        }
        if (withWidgets) addWidgets(all, level)
    }

    // What wallpaper shaders drawn on canvases need now (the pointer, the clock, all monitors).
    private fun wallpaperScene() = WallpaperScene(desktop.wallpaperShaders, desktop.renderer, desktop.pointerX, desktop.pointerY, System.nanoTime(), if (desktop.monitorList.isEmpty()) Rect(0, 0, 1, 1) else desktop.desktopBounds()) { name, code, problem -> desktop.shaderWorks(name, code, problem) }

    internal val widgets = ArrayList<Widget>()

    internal var hoveredWidget: Widget? = null

    internal var pressedWidget: PressedWidget? = null

    internal var hoveredArea: Rect? = null

    internal class PressedWidget(val widget: Widget, val area: Rect, val monitor: Monitor)

    fun addWidget(widget: Widget): AutoCloseable {
        widgets += widget
        desktop.updateWidgets()
        return AutoCloseable {
            if (!widgets.remove(widget)) return@AutoCloseable
            if (hoveredWidget === widget) {
                hoveredWidget = null
                hoveredArea = null
            }
            if (pressedWidget?.widget === widget) pressedWidget = null
            if (desktop.keyboardWidget === widget) focusWidget(null)
            desktop.updateWidgets()
            (widget as? AutoCloseable)?.let { userCode("Your widget (close)", Unit) { it.close() } }
        }
    }

    fun focusWidget(wanted: Widget?) {
        // Behind our own lock screen the keyboard goes back to it, not to a program.
        val widget = wanted ?: desktop.ownLock?.screen?.takeIf { !desktop.sessionLocked }
        val old = desktop.keyboardWidget
        if (old === widget) return
        if (widget != null && !desktop.widgetUsable(widget)) return // the lock screen keeps it
        desktop.keyboardWidget = widget
        if (old != null) userCode("Your widget (keyboardFocus)", Unit) { old.keyboardFocus(false) }
        if (widget != null) {
            desktop.inputLog.info { "The keyboard goes to the widget $widget" }
            desktop.seat.focusKeyboard(null)
            userCode("Your widget (keyboardFocus)", Unit) { widget.keyboardFocus(true) }
        } else if (!desktop.locked) {
            desktop.seat.focusKeyboard(desktop.layerHost.exclusiveLayer()?.surface ?: desktop.layerHost.layerFocus?.surface ?: desktop.focusedWindow?.surface)
        }
        desktop.changed()
    }

    // [surface] gets the keyboard (or nobody does): a widget that had it loses it then. While
    // the session is locked only the locker's surfaces may have it (nothing else takes it away).
    internal fun keyboardTo(surface: Surface?) {
        if (desktop.locked && (surface == null || !desktop.lockAllows(surface))) return
        val old = desktop.keyboardWidget
        if (old != null && surface != null) {
            desktop.keyboardWidget = null
            desktop.inputLog.info { "The widget $old loses the keyboard" }
            userCode("Your widget (keyboardFocus)", Unit) { old.keyboardFocus(false) }
            desktop.changed()
        }
        desktop.seat.focusKeyboard(surface)
    }

    // The pointer leaves our widgets (the overview opens, the screen locks): the hovered one is told.
    internal fun leaveWidgets() {
        val left = hoveredWidget ?: pressedWidget?.widget
        hoveredWidget = null
        hoveredArea = null
        pressedWidget = null
        if (left != null) {
            userCode("Your widget (pointerLeft)", Unit) { left.pointerLeft() }
            desktop.cursors.cursorChanged()
        }
    }

    // The widget the pointer is over now (and where it is there): it gets the pointer, the
    // program below loses it. With [moved] false the pointer stood still (the widget came to it).
    internal fun hoverWidget(timeMillis: Int, at: Desktop.Pointed.AtWidget?, moved: Boolean): Boolean {
        val old = hoveredWidget
        if (old != null && old !== at?.widget) {
            hoveredWidget = null
            hoveredArea = null
            userCode("Your widget (pointerLeft)", Unit) { old.pointerLeft() }
            desktop.cursors.cursorChanged()
        }
        if (at == null) return false
        val came = hoveredWidget !== at.widget
        hoveredWidget = at.widget
        hoveredArea = at.area
        if (came) desktop.cursors.cursorChanged()
        // (A drag going on hears it left the program: dropped here, nobody gets it.)
        if (desktop.seat.pointerFocus != null || desktop.seat.drag != null) desktop.seat.pointerMotion(timeMillis, null, 0.0, 0.0)
        if (moved || came) userCode("Your widget (pointerMoved)", Unit) { at.widget.pointerMoved(desktop.monitorAt(desktop.pointerX, desktop.pointerY), desktop.pointerX - at.area.x, desktop.pointerY - at.area.y) }
        return true
    }

    // The widget under ([x], [y]) at [level], topmost first, and its area there.
    internal fun widgetAt(x: Double, y: Double, level: DrawingLevel): Pair<Widget, Rect>? {
        if (widgets.isEmpty()) return null
        val monitor = desktop.monitorAt(x, y)
        for (widget in widgets.asReversed()) {
            if (desktop.locked && !desktop.widgetUsable(widget)) continue // (nothing behind the lock gets the pointer)
            if (userCode("Your widget (level)", null) { widget.level } != level) continue
            val area = userCode("Your widget (area)", null) { widget.area(monitor) } ?: continue
            if (x >= area.x && y >= area.y && x < area.right && y < area.bottom) return widget to area
        }
        return null
    }

    // The widgets of [level], on every monitor.
    internal fun addWidgets(all: MutableList<DrawItem>, level: DrawingLevel, onLockScreen: Boolean = false) {
        if (widgets.isEmpty()) return
        val seconds = ((System.nanoTime() - desktop.paintStart) / 1e9).toFloat()
        val scene = wallpaperScene()
        for (monitor in desktop.monitorList) {
            for (widget in widgets.toList()) {
                if (onLockScreen && !desktop.widgetUsable(widget)) continue
                if (userCode("Your widget (level)", null) { widget.level } != level) continue
                val area = userCode("Your widget (area)", null) { widget.area(monitor) } ?: continue
                val canvas = OverlayCanvas(monitor, listOf("widget", widget, monitor.name), seconds, scene)
                try {
                    widget.draw(monitor, canvas, area)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    desktop.log.error("Your widget $widget failed to draw (left out)", e)
                    continue
                }
                if (canvas.movingPaint) desktop.paintsMoving = true
                all += canvas.items
            }
        }
    }

    // The director's pictures with [window], below it or above it.
    internal fun addDecorations(all: MutableList<DrawItem>, window: Window, place: WindowPlace, above: Boolean) {
        val d = desktop.director ?: return
        val canvas = OverlayCanvas(null, listOf("decorations", window, above), ((System.nanoTime() - desktop.paintStart) / 1e9).toFloat())
        try {
            d.decorate(window, place, canvas, above)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Your WindowDirector failed to decorate $window (left out)", e)
            return
        }
        if (canvas.movingPaint) desktop.paintsMoving = true
        all += canvas.items
    }
}
