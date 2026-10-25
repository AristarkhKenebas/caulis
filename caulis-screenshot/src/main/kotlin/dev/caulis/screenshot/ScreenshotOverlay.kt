package dev.caulis.screenshot

import dev.caulis.core.Monitor
import dev.caulis.core.Overlay
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Window
import dev.caulis.geometry.Rect
import dev.caulis.wm.Caulis

/**
 * The chooser ([Screenshots.choose]): the monitors as they were when it opened, a part, a
 * window or a monitor marked on them, and the toolbar at the bottom of the monitor it
 * opened on.
 */
internal class ScreenshotOverlay(
    private val caulis: Caulis,
    private val shots: Screenshots,
    private val frozen: Map<Monitor, Frozen>,
) : Overlay {

    private val desktop = caulis.desktop
    private val toolbar = Toolbar(desktop.renderer)
    private val sizeLabel = Label(desktop.renderer)
    private val home: Monitor = desktop.focusedMonitor

    private var kind = shots.kind
    private var pointer = shots.pointer
    private var delay = shots.delaySeconds

    private var x = desktop.pointerX
    private var y = desktop.pointerY

    // The part chosen (desktop units) and the monitor it is on: the last one offered again.
    private var region: Rect? = shots.lastRegion[home.name]?.takeIf { home.area.contains(it) }
    private var regionOn: Monitor = home

    // What the mouse is doing with the part.
    private enum class Drag { NONE, NEW, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var dragX = 0.0
    private var dragY = 0.0
    private var dragFrom: Rect? = null
    private var edges = 0
    private var lastPress = 0L

    // The window or monitor last under the pointer (Capture on the toolbar takes it).
    private var window: Window? = null
    private var screen: Monitor = home
    private var hovered: Button? = null

    private val held = HashSet<Int>()
    private val shift get() = KEY_LEFTSHIFT in held || KEY_RIGHTSHIFT in held
    private val ctrl get() = KEY_LEFTCTRL in held || KEY_RIGHTCTRL in held

    init {
        hover()
    }

    override fun toString() = "the screenshot chooser"

    // --- drawing ----------------------------------------------------------------------------

    private fun toolbarRect(): Rect {
        val area = home.usableArea
        return Rect(area.x + (area.width - toolbar.width) / 2, area.bottom - toolbar.height - 28, toolbar.width, toolbar.height)
    }

    override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
        val picture = frozen[monitor] ?: return // (plugged in since: shown as it is)
        val area = monitor.area
        canvas.image(picture.texture, area)
        when (kind) {
            ShotKind.REGION -> {
                val part = region?.takeIf { regionOn === monitor }?.intersect(area)
                if (part == null) {
                    canvas.fill(area, 0f, 0f, 0f, DIM)
                } else {
                    dimAround(canvas, area, part)
                    canvas.frame(grow(part, 1), 1, 1f, 1f, 1f, 0.95f)
                    if (drag == Drag.NONE || drag == Drag.RESIZE) handles(canvas, part)
                    val text = picture.toPixels(part).let { "${it.width} × ${it.height}" }
                    val (w, h) = sizeLabel.size(text)
                    val below = part.bottom + 8
                    val labelY = if (below + h <= area.bottom) below else (part.y - h - 8).coerceAtLeast(area.y)
                    canvas.image(sizeLabel.texture(text, monitor.scale), Rect(part.x, labelY, w, h))
                }
            }
            ShotKind.WINDOW -> {
                val shown = window?.let(desktop::screenRect)?.intersect(area)
                if (shown == null) {
                    canvas.fill(area, 0f, 0f, 0f, DIM)
                } else {
                    dimAround(canvas, area, shown)
                    canvas.frame(shown, 3, ACCENT[0], ACCENT[1], ACCENT[2], 0.9f)
                }
            }
            ShotKind.SCREEN -> {
                if (monitor === screen) canvas.frame(area, 4, ACCENT[0], ACCENT[1], ACCENT[2], 0.9f) else canvas.fill(area, 0f, 0f, 0f, DIM)
            }
        }
        if (monitor === home) {
            canvas.image(toolbar.texture(Toolbar.Look(kind, pointer, delay, hovered, monitor.scale)), toolbarRect(), radius = 20f)
        }
    }

    private fun dimAround(canvas: OverlayCanvas, area: Rect, hole: Rect) {
        canvas.fill(Rect(area.x, area.y, area.width, hole.y - area.y), 0f, 0f, 0f, DIM)
        canvas.fill(Rect(area.x, hole.bottom, area.width, area.bottom - hole.bottom), 0f, 0f, 0f, DIM)
        canvas.fill(Rect(area.x, hole.y, hole.x - area.x, hole.height), 0f, 0f, 0f, DIM)
        canvas.fill(Rect(hole.right, hole.y, area.right - hole.right, hole.height), 0f, 0f, 0f, DIM)
    }

    private fun handles(canvas: OverlayCanvas, part: Rect) {
        val xs = listOf(part.x, part.x + part.width / 2, part.right)
        val ys = listOf(part.y, part.y + part.height / 2, part.bottom)
        for ((i, hx) in xs.withIndex()) for ((j, hy) in ys.withIndex()) {
            if (i == 1 && j == 1) continue
            val dot = Rect(hx - HANDLE / 2, hy - HANDLE / 2, HANDLE, HANDLE)
            canvas.fill(grow(dot, 1), 0.1f, 0.1f, 0.12f, 0.6f)
            canvas.fill(dot, 1f, 1f, 1f)
        }
    }

    // --- the pointer ------------------------------------------------------------------------

    override val cursor: String
        get() = when {
            hovered != null -> "pointer"
            kind != ShotKind.REGION -> "pointer"
            drag == Drag.MOVE -> "grabbing"
            drag == Drag.NEW -> "crosshair"
            drag == Drag.RESIZE -> resizeCursor(edges)
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
        when (drag) {
            Drag.NEW -> region = clamp(between(dragX, dragY, x, y), regionOn.area)
            Drag.MOVE -> if (from != null) region = moved(from, (x - dragX).toInt(), (y - dragY).toInt())
            Drag.RESIZE -> if (from != null) region = resized(from, edges, (x - dragX).toInt(), (y - dragY).toInt())
            Drag.NONE -> {}
        }
        hover()
        desktop.redrawOverlay()
    }

    // What is under the pointer: a button, a window, a monitor.
    private fun hover() {
        hovered = toolbar.buttonAt(toolbarRect(), x, y).takeIf { desktop.monitorAt(x, y) === home }
        if (hovered != null || drag != Drag.NONE) return
        screen = desktop.monitorAt(x, y)
        window = desktop.windowAt(x, y) ?: window?.takeIf { kind != ShotKind.WINDOW }
    }

    override fun pointerButton(button: Int, pressed: Boolean) {
        if (button == BTN_RIGHT && pressed) return cancel()
        if (button != BTN_LEFT) return
        if (!pressed) return released()
        hovered?.let { return click(it) }
        when (kind) {
            ShotKind.WINDOW -> window?.let { take(ShotKind.WINDOW) }
            ShotKind.SCREEN -> take(ShotKind.SCREEN)
            ShotKind.REGION -> {
                val now = System.nanoTime()
                val doubleClick = now - lastPress < DOUBLE_CLICK_NANOS
                lastPress = now
                val on = desktop.monitorAt(x, y)
                val part = region?.takeIf { regionOn === on }
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
        if (drag == Drag.NEW) {
            val part = region
            // A click, not a drag: the window there (or nothing).
            if (part == null || part.width < MIN_DRAG || part.height < MIN_DRAG) {
                region = desktop.windowAt(dragX, dragY)?.let(desktop::screenRect)?.intersect(regionOn.area)
            }
        }
        drag = Drag.NONE
        dragFrom = null
        hover()
        desktop.redrawOverlay()
    }

    private fun click(button: Button) {
        when (button) {
            Button.REGION -> kind = ShotKind.REGION
            Button.WINDOW -> kind = ShotKind.WINDOW
            Button.SCREEN -> kind = ShotKind.SCREEN
            Button.POINTER -> pointer = !pointer
            Button.TIMER -> delay = DELAYS[(DELAYS.indexOf(delay) + 1) % DELAYS.size]
            Button.TAKE -> return take(kind)
            Button.CLOSE -> return cancel()
        }
        desktop.redrawOverlay()
    }

    // --- keys -------------------------------------------------------------------------------

    override fun key(evdevCode: Int, pressed: Boolean) {
        if (!pressed) {
            held -= evdevCode
            return
        }
        held += evdevCode
        val step = if (shift) 10 else 1
        when (evdevCode) {
            KEY_ESC -> if (drag != Drag.NONE) {
                region = dragFrom
                drag = Drag.NONE
            } else {
                return cancel()
            }
            KEY_ENTER, KEY_KPENTER, KEY_SPACE -> return take(kind)
            KEY_C -> if (ctrl) return take(kind, copyOnly = true)
            KEY_TAB -> kind = ShotKind.entries[(kind.ordinal + if (shift) ShotKind.entries.size - 1 else 1) % ShotKind.entries.size]
            KEY_R, KEY_A -> kind = ShotKind.REGION
            KEY_W -> kind = ShotKind.WINDOW
            KEY_S -> kind = ShotKind.SCREEN
            KEY_P -> pointer = !pointer
            KEY_T -> delay = DELAYS[(DELAYS.indexOf(delay) + 1) % DELAYS.size]
            KEY_LEFT, KEY_RIGHT, KEY_UP, KEY_DOWN -> {
                val part = region ?: return
                val dx = when (evdevCode) { KEY_LEFT -> -step; KEY_RIGHT -> step; else -> 0 }
                val dy = when (evdevCode) { KEY_UP -> -step; KEY_DOWN -> step; else -> 0 }
                // Ctrl: the right and bottom edges move (the size changes).
                region = if (ctrl) resized(part, RIGHT or BOTTOM, dx, dy) else moved(part, dx, dy)
            }
            else -> return
        }
        desktop.redrawOverlay()
    }

    // --- taking -----------------------------------------------------------------------------

    private fun take(what: ShotKind, copyOnly: Boolean = false) {
        shots.kind = kind
        shots.pointer = pointer
        shots.delaySeconds = delay
        when (what) {
            ShotKind.REGION -> {
                val part = region ?: return desktop.showLevel("Drag to choose a part first")
                val monitor = regionOn
                shots.lastRegion[monitor.name] = part
                val picture = if (delay == 0) frozen[monitor]?.read(part, pointer) else null
                desktop.closeOverlay()
                if (picture != null) {
                    shots.deliver(picture, "a part of ${monitor.name}", copyOnly)
                } else {
                    shots.later { Frozen.capture(desktop, monitor, pointer).use { shots.deliver(it.read(part), "a part of ${monitor.name}", copyOnly) } }
                }
            }
            ShotKind.WINDOW -> {
                val chosen = window ?: return desktop.showLevel("Point at a window first")
                desktop.closeOverlay()
                shots.later { if (chosen in desktop.allWindows) shots.takeWindow(chosen) }
            }
            ShotKind.SCREEN -> {
                val monitor = screen
                val picture = if (delay == 0) frozen[monitor]?.read(null, pointer) else null
                desktop.closeOverlay()
                if (picture != null) shots.deliver(picture, "screen ${monitor.name}", copyOnly) else shots.later { shots.takeScreen(monitor) }
            }
        }
    }

    private fun cancel() {
        shots.kind = kind
        shots.pointer = pointer
        shots.delaySeconds = delay
        region?.let { shots.lastRegion[regionOn.name] = it }
        desktop.closeOverlay()
    }

    override fun closed() {
        frozen.values.forEach(Frozen::close)
        toolbar.close()
        sizeLabel.close()
    }

    // --- shapes -----------------------------------------------------------------------------

    private fun inside(r: Rect, px: Double, py: Double) = px >= r.x && py >= r.y && px < r.right && py < r.bottom

    // Which edges of [r] are near the point (LEFT, RIGHT, TOP, BOTTOM), 0 if none.
    private fun edgesNear(r: Rect, px: Double, py: Double): Int {
        if (px < r.x - GRAB || px > r.right + GRAB || py < r.y - GRAB || py > r.bottom + GRAB) return 0
        var near = 0
        if (Math.abs(px - r.x) <= GRAB) near = near or LEFT else if (Math.abs(px - r.right) <= GRAB) near = near or RIGHT
        if (Math.abs(py - r.y) <= GRAB) near = near or TOP else if (Math.abs(py - r.bottom) <= GRAB) near = near or BOTTOM
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

    private fun grow(r: Rect, by: Int) = Rect(r.x - by, r.y - by, r.width + 2 * by, r.height + 2 * by)

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

    private companion object {
        const val DIM = 0.4f
        val ACCENT = floatArrayOf(0.24f, 0.55f, 0.99f)
        const val HANDLE = 8
        const val GRAB = 6
        const val MIN_DRAG = 4
        const val DOUBLE_CLICK_NANOS = 400_000_000L
        val DELAYS = listOf(0, 3, 5, 10)

        const val LEFT = 1
        const val RIGHT = 2
        const val TOP = 4
        const val BOTTOM = 8

        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273

        const val KEY_ESC = 1
        const val KEY_TAB = 15
        const val KEY_W = 17
        const val KEY_R = 19
        const val KEY_T = 20
        const val KEY_P = 25
        const val KEY_ENTER = 28
        const val KEY_LEFTCTRL = 29
        const val KEY_A = 30
        const val KEY_S = 31
        const val KEY_LEFTSHIFT = 42
        const val KEY_C = 46
        const val KEY_RIGHTSHIFT = 54
        const val KEY_SPACE = 57
        const val KEY_KPENTER = 96
        const val KEY_RIGHTCTRL = 97
        const val KEY_UP = 103
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_DOWN = 108
    }
}

// The rectangle between two points (in any order), at least one unit.
private fun between(x0: Double, y0: Double, x1: Double, y1: Double): Rect {
    val left = minOf(x0, x1).toInt()
    val top = minOf(y0, y1).toInt()
    return Rect(left, top, maxOf(1, Math.abs(x1 - x0).toInt()), maxOf(1, Math.abs(y1 - y0).toInt()))
}
