package dev.caulis.screenshot

import dev.caulis.core.Monitor
import dev.caulis.core.Overlay
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Window
import dev.caulis.geometry.Rect
import dev.caulis.wm.Caulis

/**
 * The chooser ([Screenshots.choose]): the monitors as they were when it opened, a part, a
 * window, a monitor or several windows marked on them, and the toolbar at the bottom of the
 * monitor it opened on (the name of the button under the pointer above it).
 */
internal class ScreenshotOverlay(
    internal val caulis: Caulis,
    internal val shots: Screenshots,
    internal val frozen: Map<Monitor, Frozen>,
    // The windows on each monitor where they were drawn when it opened, bottom to top.
    internal val windows: Map<Monitor, List<Pair<Window, Rect>>>,
    overview: Boolean,
    start: ShotKind,
) : Overlay {
    internal val painter = ScreenshotOverlayPainter(this)

    internal val desktop = caulis.desktop
    internal val home: Monitor = desktop.focusedMonitor
    internal val style = shots.style
    internal val words = style.words
    internal val behaviour = shots.chooser
    internal val keys = behaviour.keys
    // The keys' names in the keyboard layout (the chooser's keys are looked up by them).
    private val keyNames = shots.keyNames()
    internal val toolbar = Toolbar(desktop.renderer, behaviour.buttons.distinct().filter { it != ChooserButton.WINDOWS || overview }, style)
    internal val label = Label(desktop.renderer)
    internal val tip = Label(desktop.renderer)

    internal var kind = start
    internal var selection = shots.selection
    internal var pointer = shots.pointer
    internal var delay = shots.delaySeconds

    private var x = desktop.pointerX
    private var y = desktop.pointerY

    // The part chosen (desktop units) and the monitor it is on: the last one offered again
    // (ChooserBehaviour.rememberSelection), while the monitors are as they were.
    private val offered = shots.rememberedPart()
    internal var region: Rect? = offered?.second
    internal var regionOn: Monitor = offered?.first ?: home

    // The windows chosen (WINDOWS), and those a frame being dragged goes over.
    internal val picked = LinkedHashSet<Window>()
    internal var lasso: Rect? = null
    private var pickedBefore: Set<Window> = emptySet()

    // What the mouse is doing.
    internal enum class Drag { NONE, NEW, MOVE, RESIZE, LASSO }
    internal var drag = Drag.NONE
    private var dragX = 0.0
    private var dragY = 0.0
    private var dragFrom: Rect? = null
    private var edges = 0
    private var lastPress = 0L

    // The window or monitor last under the pointer (Capture on the toolbar takes it).
    internal var window: Window? = null
    internal var screen: Monitor = home
    internal var hovered: ChooserButton? = null

    private val held = HashSet<Int>()
    private val shift get() = KEY_LEFTSHIFT in held || KEY_RIGHTSHIFT in held
    private val ctrl get() = KEY_LEFTCTRL in held || KEY_RIGHTCTRL in held

    // Coming in: the screen darkens and the toolbar rises, quickly.
    internal val openedAt = System.nanoTime()
    internal var ticking = false

    init {
        hover()
        shots.log.trace { "The chooser: ${kind.name.lowercase()}, choosing a part: ${selection.name.lowercase()}${if (offered != null) ", the part chosen last offered again" else ""}" }
    }

    override fun toString() = "the screenshot chooser"

    // (For tests: the part chosen, and where a button of the toolbar is — its middle — if it is there.)
    internal val part: Rect? get() = region
    internal fun placeOf(button: ChooserButton): Pair<Double, Double>? {
        val at = toolbarRect()
        val range = toolbar.places(clearShown())[button] ?: return null
        return at.x + (range.first + range.last + 1) / 2.0 to at.y + at.height / 2.0
    }

    internal fun toolbarRect(): Rect {
        val area = home.usableArea
        val width = toolbar.width(clearShown())
        return Rect(area.x + (area.width - width) / 2, area.bottom - toolbar.height - style.toolbarMargin, width, toolbar.height)
    }

    // True if a part is shown (choosing a part, one chosen).
    private fun partShown(): Boolean = kind == ShotKind.REGION && region != null

    // True if the clear button is on the toolbar: a part is shown (or being drawn).
    internal fun clearShown(): Boolean = ChooserButton.CLEAR in toolbar.buttons && (partShown() || (kind == ShotKind.REGION && drag == Drag.NEW))

    override fun draw(monitor: Monitor, canvas: OverlayCanvas) = painter.draw(monitor, canvas)

    // Where [w] was drawn on [monitor] when the chooser opened (null: not there).
    internal fun windowRect(w: Window, monitor: Monitor): Rect? = windows[monitor]?.firstOrNull { it.first === w }?.second

    // The topmost window at [px], [py] on [monitor] when the chooser opened.
    private fun windowAt(monitor: Monitor, px: Double, py: Double): Pair<Window, Rect>? = windows[monitor].orEmpty().lastOrNull { inside(it.second, px, py) }

    // Its corners' radius on screen (drawn smaller: smaller corners).
    internal fun radiusOf(w: Window, r: Rect, monitor: Monitor): Float {
        val (pw, _) = desktop.windowPixelSize(w)
        return (shots.sharpCornerRadius * monitor.scale * r.width / pw.coerceAtLeast(1)).toFloat()
    }

    // The windows chosen, with those under a frame being dragged.
    internal fun chosenNow(): Set<Window> {
        val l = lasso ?: return picked
        val under = windows[regionOn].orEmpty().filter { it.second.intersect(l) != null }.map { it.first }
        return pickedBefore + under
    }

    // --- the pointer ------------------------------------------------------------------------

    override val cursor: String
        get() = when {
            hovered != null -> "pointer"
            kind == ShotKind.WINDOW || kind == ShotKind.SCREEN -> "pointer"
            kind == ShotKind.WINDOWS -> if (drag == Drag.LASSO) "crosshair" else if (window != null) "pointer" else "crosshair"
            drag == Drag.MOVE -> "grabbing"
            drag == Drag.NEW -> "crosshair"
            drag == Drag.RESIZE -> resizeCursor(edges)
            selection == Screenshots.Selection.NEW -> "crosshair"
            else -> {
                val part = region?.takeIf { regionOn === desktop.monitorAt(x, y) }
                val near = part?.let { edgesNear(it, x, y) } ?: 0
                when {
                    near != 0 -> resizeCursor(near)
                    part != null && inside(part, x, y) -> "move"
                    else -> "crosshair"
                }
            }
        }

    override fun pointerMoved(x: Double, y: Double) {
        this.x = x
        this.y = y
        val from = dragFrom
        val before = Triple(hovered, window, screen)
        when (drag) {
            Drag.NEW -> region = clamp(between(dragX, dragY, x, y), regionOn.area)
            Drag.MOVE -> if (from != null) region = moved(from, (x - dragX).toInt(), (y - dragY).toInt())
            Drag.RESIZE -> if (from != null) region = resized(from, edges, (x - dragX).toInt(), (y - dragY).toInt())
            Drag.LASSO -> lasso = clamp(between(dragX, dragY, x, y), regionOn.area).takeIf { it.width >= behaviour.minDrag || it.height >= behaviour.minDrag }
            Drag.NONE -> {}
        }
        hover()
        // (Only what changed is drawn again: the pointer alone moves on its own.)
        if (drag != Drag.NONE || before != Triple(hovered, window, screen)) desktop.redrawOverlay()
    }

    // What is under the pointer: a button, a window, a monitor.
    private fun hover() {
        hovered = toolbar.buttonAt(toolbarRect(), x, y, clearShown()).takeIf { desktop.monitorAt(x, y) === home }
        if (hovered != null || drag != Drag.NONE) return
        screen = desktop.monitorAt(x, y)
        // (Where the windows were drawn when it opened: the topmost there.)
        val under = windowAt(screen, x, y)?.first
        window = if (kind == ShotKind.WINDOWS) under else under ?: window
    }

    override fun pointerButton(button: Int, pressed: Boolean) {
        if (button == BTN_RIGHT && pressed) return cancel()
        if (button != BTN_LEFT) return
        if (!pressed) return released()
        hovered?.let { return click(it) }
        val now = System.nanoTime()
        val doubleClick = now - lastPress < behaviour.doubleClickMillis * 1_000_000
        lastPress = now
        when (kind) {
            ShotKind.WINDOW -> window?.let { take(ShotKind.WINDOW) }
            ShotKind.SCREEN -> take(ShotKind.SCREEN)
            ShotKind.WINDOWS -> {
                if (doubleClick && window != null && window in picked) return take(ShotKind.WINDOWS)
                val on = desktop.monitorAt(x, y)
                if (on !== regionOn) picked.clear()
                regionOn = on
                drag = Drag.LASSO
                dragX = x
                dragY = y
                lasso = null
                // Shift (or Ctrl) adds to the windows chosen; else a frame chooses anew.
                pickedBefore = if (shift || ctrl) picked.toSet() else emptySet()
            }
            ShotKind.REGION -> {
                val on = desktop.monitorAt(x, y)
                val part = region?.takeIf { regionOn === on && selection == Screenshots.Selection.ADJUST }
                val near = part?.let { edgesNear(it, x, y) } ?: 0
                dragX = x
                dragY = y
                dragFrom = part
                when {
                    near != 0 -> {
                        drag = Drag.RESIZE
                        edges = near
                    }
                    part != null && inside(part, x, y) -> {
                        if (doubleClick) return take(ShotKind.REGION)
                        drag = Drag.MOVE
                    }
                    else -> {
                        // (Each drag a new part: a double click inside the last one still takes it.)
                        if (doubleClick && region?.let { regionOn === on && inside(it, x, y) } == true) return take(ShotKind.REGION)
                        dragFrom = region
                        drag = Drag.NEW
                        regionOn = on
                        region = null
                    }
                }
            }
        }
        desktop.redrawOverlay()
    }

    private fun released() {
        when (drag) {
            Drag.NEW -> {
                val part = region
                // A click, not a drag: the window there as it was drawn, if clickPicksWindow says so (else nothing).
                if (part == null || part.width < behaviour.minDrag || part.height < behaviour.minDrag) {
                    region = if (behaviour.clickPicksWindow) windowAt(regionOn, dragX, dragY)?.second?.intersect(regionOn.area) else null
                    if (region != null) shots.log.trace { "A click picked the window under it as the part" }
                }
            }
            Drag.LASSO -> {
                val l = lasso
                if (l == null) {
                    // A click: that window in or out.
                    val w = windowAt(regionOn, dragX, dragY)?.first
                    if (w != null && !picked.remove(w)) picked += w
                    if (w == null && !(shift || ctrl)) picked.clear()
                } else {
                    val chosen = chosenNow()
                    picked.clear()
                    picked += windows[regionOn].orEmpty().map { it.first }.filter { it in chosen }
                }
                lasso = null
                pickedBefore = emptySet()
                shots.log.trace { "Windows chosen: ${picked.joinToString { it.appId }}" }
            }
            else -> {}
        }
        drag = Drag.NONE
        dragFrom = null
        hover()
        desktop.redrawOverlay()
    }

    private fun click(button: ChooserButton) {
        when (button) {
            ChooserButton.REGION -> kind = ShotKind.REGION
            ChooserButton.WINDOW -> kind = ShotKind.WINDOW
            ChooserButton.SCREEN -> kind = ShotKind.SCREEN
            ChooserButton.WINDOWS -> kind = ShotKind.WINDOWS
            ChooserButton.SELECTION -> toggleSelection()
            ChooserButton.CLEAR -> clearPart()
            ChooserButton.POINTER -> pointer = !pointer
            ChooserButton.TIMER -> nextDelay()
            ChooserButton.TAKE -> return take(kind)
            ChooserButton.CLOSE -> return cancel()
        }
        // (The toolbar may have changed its width: the clear button in or out.)
        hover()
        desktop.redrawOverlay()
    }

    // The part chosen is cleared, and not offered again.
    private fun clearPart() {
        region = null
        shots.forgetPart()
        shots.log.trace { "The part chosen was cleared" }
    }

    // The timer's next step (after the last: the first).
    private fun nextDelay() {
        val steps = behaviour.timerSteps
        delay = steps[(steps.indexOf(delay) + 1) % steps.size]
    }

    private fun toggleSelection() {
        selection = if (selection == Screenshots.Selection.ADJUST) Screenshots.Selection.NEW else Screenshots.Selection.ADJUST
        if (kind != ShotKind.REGION) kind = ShotKind.REGION
        shots.log.trace { "Choosing a part: ${selection.name.lowercase()}" }
    }

    // --- keys -------------------------------------------------------------------------------

    override fun key(evdevCode: Int, pressed: Boolean) {
        if (!pressed) {
            held -= evdevCode
            return
        }
        held += evdevCode
        val step = if (shift) 10 else 1
        val kinds = toolbar.buttons.filter { it.isKind }.map { ShotKind.valueOf(it.name) }
        // The key by its name in the layout ("a" is where the layout has it).
        val name = keyNames[evdevCode]
        when {
            keys.close.has(name) -> when {
                drag == Drag.LASSO -> {
                    lasso = null
                    drag = Drag.NONE
                }
                drag != Drag.NONE -> {
                    region = dragFrom
                    drag = Drag.NONE
                }
                // A part shown: cleared first (a second Escape closes), with escapeClearsSelection.
                behaviour.escapeClearsSelection && partShown() -> clearPart()
                else -> {
                    // (Else Escape closes and the part is forgotten.)
                    if (!behaviour.escapeClearsSelection && region != null) clearPart()
                    return cancel()
                }
            }
            ctrl && keys.copy.has(name) -> return take(kind, copyOnly = true)
            ctrl && kind == ShotKind.WINDOWS && keys.all.has(name) -> {
                // All the windows on the monitor with the pointer.
                regionOn = desktop.monitorAt(x, y)
                picked.clear()
                picked += windows[regionOn].orEmpty().map { it.first }
            }
            keys.capture.has(name) -> return take(kind)
            keys.nextKind.has(name) -> if (kinds.isNotEmpty()) kind = kinds[(kinds.indexOf(kind) + if (shift) kinds.size - 1 else 1) % kinds.size]
            keys.area.has(name) -> kind = ShotKind.REGION
            keys.window.has(name) -> kind = ShotKind.WINDOW
            keys.screen.has(name) -> kind = ShotKind.SCREEN
            keys.windows.has(name) -> if (ShotKind.WINDOWS in kinds) kind = ShotKind.WINDOWS else return
            keys.selection.has(name) -> toggleSelection()
            keys.pointer.has(name) -> pointer = !pointer
            keys.timer.has(name) -> nextDelay()
            evdevCode == KEY_LEFT || evdevCode == KEY_RIGHT || evdevCode == KEY_UP || evdevCode == KEY_DOWN -> {
                val part = region ?: return
                if (kind != ShotKind.REGION) return
                val dx = when (evdevCode) { KEY_LEFT -> -step; KEY_RIGHT -> step; else -> 0 }
                val dy = when (evdevCode) { KEY_UP -> -step; KEY_DOWN -> step; else -> 0 }
                // Ctrl: the right and bottom edges move (the size changes).
                region = if (ctrl) resized(part, RIGHT or BOTTOM, dx, dy) else moved(part, dx, dy)
            }
            else -> return
        }
        shots.log.trace { "The chooser's key: $name" }
        hover()
        desktop.redrawOverlay()
    }

    // --- taking -----------------------------------------------------------------------------

    private fun remember() {
        // (Over an overview the kind it starts with is its own: what was used anywhere else stays.)
        if (kind != ShotKind.WINDOWS && behaviour.rememberKind) shots.kind = kind
        shots.selection = selection
        shots.pointer = pointer
        shots.delaySeconds = delay
        // The part, to be offered again (none: none next time either).
        val part = region
        if (part != null) shots.rememberPart(regionOn, part) else shots.forgetPart()
    }

    private fun take(what: ShotKind, copyOnly: Boolean = false) {
        remember()
        when (what) {
            ShotKind.REGION -> {
                val part = region ?: return desktop.showLevel(words.dragFirst)
                val monitor = regionOn
                // Windows drawn smaller than they are (an overview): put together from them, sharp.
                val sharp = if (delay == 0 && shots.sharpWindows) shots.sharpRegion(monitor, part, windows[monitor].orEmpty(), frozen[monitor]) else null
                val picture = if (sharp == null && delay == 0) frozen[monitor]?.read(part, pointer) else null
                desktop.closeOverlay()
                when {
                    sharp != null -> shots.deliver(sharp::draw, "a part of ${monitor.name}", ShotKind.REGION, copyOnly)
                    picture != null -> shots.deliver(picture, "a part of ${monitor.name}", ShotKind.REGION, copyOnly)
                    else -> shots.later { Frozen.capture(desktop, monitor, pointer).use { shots.deliver(it.read(part), "a part of ${monitor.name}", ShotKind.REGION, copyOnly) } }
                }
            }
            ShotKind.WINDOW -> {
                val chosen = window ?: return desktop.showLevel(words.pointFirst)
                desktop.closeOverlay()
                shots.later { shots.takeWindow(chosen, copyOnly) }
            }
            ShotKind.SCREEN -> {
                val monitor = screen
                val picture = if (delay == 0) frozen[monitor]?.read(null, pointer) else null
                desktop.closeOverlay()
                if (picture != null) shots.deliver(picture, "screen ${monitor.name}", ShotKind.SCREEN, copyOnly) else shots.later { shots.takeScreen(monitor, copyOnly) }
            }
            ShotKind.WINDOWS -> {
                val monitor = regionOn
                val chosen = windows[monitor].orEmpty().filter { it.first in picked }
                if (chosen.isEmpty()) return desktop.showLevel(words.pickFirst)
                val what = "${chosen.size} windows of ${monitor.name}"
                if (delay == 0) {
                    // (Before closing: the screen behind them is read from the picture it shows.)
                    val picture = shots.windowsPicture(monitor, chosen, frozen[monitor])
                    desktop.closeOverlay()
                    picture?.let { shots.deliver(it::draw, what, ShotKind.WINDOWS, copyOnly) }
                } else {
                    desktop.closeOverlay()
                    // Later: where they are then.
                    shots.later {
                        val now = chosen.mapNotNull { (w, _) -> if (w in desktop.allWindows) desktop.shownRect(w)?.let { w to it } else null }
                        Frozen.capture(desktop, monitor, false).use { screen -> shots.windowsPicture(monitor, now, screen)?.let { shots.deliver(it::draw, what, ShotKind.WINDOWS, copyOnly) } }
                    }
                }
            }
        }
    }

    private fun cancel() {
        remember()
        desktop.closeOverlay()
    }

    override fun closed() {
        frozen.values.forEach(Frozen::close)
        toolbar.close()
        label.close()
        tip.close()
    }

    // --- shapes -----------------------------------------------------------------------------

    private fun inside(r: Rect, px: Double, py: Double) = px >= r.x && py >= r.y && px < r.right && py < r.bottom

    // Which edges of [r] are near the point (LEFT, RIGHT, TOP, BOTTOM), 0 if none.
    private fun edgesNear(r: Rect, px: Double, py: Double): Int {
        val grab = behaviour.grab
        if (px < r.x - grab || px > r.right + grab || py < r.y - grab || py > r.bottom + grab) return 0
        var near = 0
        if (Math.abs(px - r.x) <= grab) near = near or LEFT else if (Math.abs(px - r.right) <= grab) near = near or RIGHT
        if (Math.abs(py - r.y) <= grab) near = near or TOP else if (Math.abs(py - r.bottom) <= grab) near = near or BOTTOM
        return near
    }

    private fun moved(r: Rect, dx: Int, dy: Int): Rect {
        val area = regionOn.area
        val x = (r.x + dx).coerceIn(area.x, area.right - r.width)
        val y = (r.y + dy).coerceIn(area.y, area.bottom - r.height)
        return Rect(x, y, r.width, r.height)
    }

    private fun resized(r: Rect, edges: Int, dx: Int, dy: Int): Rect {
        var left = r.x
        var top = r.y
        var right = r.right
        var bottom = r.bottom
        if (edges and LEFT != 0) left += dx
        if (edges and RIGHT != 0) right += dx
        if (edges and TOP != 0) top += dy
        if (edges and BOTTOM != 0) bottom += dy
        return clamp(Rect(minOf(left, right), minOf(top, bottom), maxOf(1, Math.abs(right - left)), maxOf(1, Math.abs(bottom - top))), regionOn.area)
    }

    private fun clamp(r: Rect, area: Rect): Rect = r.intersect(area) ?: Rect(r.x.coerceIn(area.x, area.right - 1), r.y.coerceIn(area.y, area.bottom - 1), 1, 1)

    internal fun grow(r: Rect, by: Int) = Rect(r.x - by, r.y - by, r.width + 2 * by, r.height + 2 * by)

    private fun resizeCursor(edges: Int): String = when (edges) {
        TOP -> "n-resize"
        BOTTOM -> "s-resize"
        LEFT -> "w-resize"
        RIGHT -> "e-resize"
        TOP or LEFT -> "nw-resize"
        TOP or RIGHT -> "ne-resize"
        BOTTOM or LEFT -> "sw-resize"
        else -> "se-resize"
    }

}

// The rectangle between two points (in any order), at least one unit.
private fun between(x0: Double, y0: Double, x1: Double, y1: Double): Rect {
    val left = minOf(x0, x1).toInt()
    val top = minOf(y0, y1).toInt()
    return Rect(left, top, maxOf(1, Math.abs(x1 - x0).toInt()), maxOf(1, Math.abs(y1 - y0).toInt()))
}

internal const val LEFT = 1
internal const val RIGHT = 2
internal const val TOP = 4
internal const val BOTTOM = 8

internal const val BTN_LEFT = 272
internal const val BTN_RIGHT = 273

// (Modifiers and arrows by their place: the same in every layout.)
internal const val KEY_LEFTCTRL = 29
internal const val KEY_LEFTSHIFT = 42
internal const val KEY_RIGHTSHIFT = 54
internal const val KEY_RIGHTCTRL = 97
internal const val KEY_UP = 103
internal const val KEY_LEFT = 105
internal const val KEY_RIGHT = 106
internal const val KEY_DOWN = 108
