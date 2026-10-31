package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.log.Logger

// What the parts of the desktop share: their constants, and how they call your code.

internal const val FADE_KEY = "the frame before the scale change"
internal const val CURSOR_BORDER = "cursor border"
internal const val CURSOR_FILL = "cursor fill"
internal const val LOCK_BACKGROUND = "lock background"
internal const val CURSOR_IMAGE = "cursor"
internal const val BTN_LEFT = 0x110

// Ctrl, Shift, Alt, AltGr, Super (left and right): pressing them alone is not typing.
internal val MODIFIER_KEYS = setOf(29, 97, 42, 54, 56, 100, 125, 126)

/**
 * How long a layout change waits for every window to draw in its new size before
 * moving them anyway (a program that does not answer must not hold up the others).
 */
internal const val TRANSACTION_TIMEOUT_MILLIS = 200L

internal const val KEY_ESC = 1

internal const val ANCHOR_TOP = 1
internal const val ANCHOR_BOTTOM = 2
internal const val ANCHOR_LEFT = 4
internal const val ANCHOR_RIGHT = 8
internal const val KEYBOARD_NONE = 0
internal const val KEYBOARD_EXCLUSIVE = 1

/** How often programs nobody sees may draw a frame (they must not freeze completely). */
internal const val HIDDEN_REFRESH_MILLIS = 1000L

// (The same area as the desktop's own log: what your code does wrong is a matter of configuration.)
private val userCodeLog = Logger("config")

// Your code (a rule, a curve, a layout, effects): what it throws is logged and [fallback]
// used, so one mistake never stops the drawing or the desktop.
internal inline fun <T> userCode(what: String, fallback: T, body: () -> T): T = try {
    body()
} catch (e: Throwable) {
    e.rethrowIfFatal()
    userCodeLog.error("$what failed (ignored): $e", e)
    fallback
}

// A curve's point, guarded (a curve of yours may throw): straight on if it does.
internal fun Curve.safeAt(t: Double): Double = userCode("Your animation curve", t) { at(t) }

// Calls listeners one by one; a mistake in one of them is reported and the rest go on
// (your code must never be able to bring the whole desktop down).
internal fun <T> notifyEach(listeners: List<(T) -> Unit>, value: T, event: String) {
    for (listener in listeners.toList()) {
        try {
            listener(value)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            userCodeLog.error("Your $event handler failed (ignored): $e", e)
        }
    }
}

// A rectangle's size in pixels or units squared.
internal fun Rect.area() = width.toLong() * height

// Java reads the system's fonts the first time text is measured — a second or more on a
// cold start. Done on a thread of its own as soon as a desktop exists, so the first title
// bar or notice does not stop the compositor for it. (Once per program.)
private val fontsWarm = java.util.concurrent.atomic.AtomicBoolean()

internal fun warmUpFonts() {
    if (!fontsWarm.compareAndSet(false, true)) return
    Thread.ofPlatform().daemon().name("caulis-fonts").start {
        val log = Logger("render")
        val start = System.nanoTime()
        try {
            val g = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE).createGraphics()
            try {
                for (style in listOf(java.awt.Font.PLAIN, java.awt.Font.BOLD)) {
                    g.font = java.awt.Font(java.awt.Font.SANS_SERIF, style, 13)
                    g.fontMetrics.stringWidth("Caulis Каулис")
                }
            } finally {
                g.dispose()
            }
            log.info { "Fonts ready in ${(System.nanoTime() - start) / 1_000_000} ms" }
        } catch (e: Throwable) {
            log.warn("Fonts could not be read ahead: $e")
        }
    }
}

/**
 * Where the pointer at ([x], [y]) is after moving by ([dx], [dy]) over monitors at [areas]
 * that touch ([MonitorEdges.TOUCHING]): as [Desktop.pointerMovedBy] and its movePointer do it
 * — onto the monitor there, else sliding along the edge — for the input thread's early cursor.
 */
internal fun movedOver(areas: List<Rect>, x: Double, y: Double, dx: Double, dy: Double): Pair<Double, Double> {
    if (areas.isEmpty()) return (x + dx) to (y + dy)
    fun Rect.has(px: Double, py: Double) = px >= this.x && px < right && py >= this.y && py < bottom
    fun nearest(px: Double, py: Double): Rect = areas.firstOrNull { it.has(px, py) } ?: areas.minBy { a ->
        val ox = maxOf(a.x - px, 0.0, px - (a.right - 1))
        val oy = maxOf(a.y - py, 0.0, py - (a.bottom - 1))
        ox * ox + oy * oy
    }
    val from = nearest(x, y)
    var nx = x + dx
    var ny = y + dy
    if (!from.has(nx, ny) && areas.none { it.has(nx, ny) }) {
        val alongX = nx.coerceIn(from.x.toDouble(), from.right - 1.0)
        val alongY = ny.coerceIn(from.y.toDouble(), from.bottom - 1.0)
        val sideways = areas.firstOrNull { it !== from && it.has(nx, alongY) }?.let { nx to alongY }
            ?: areas.firstOrNull { it !== from && it.has(alongX, ny) }?.let { alongX to ny }
        val (sx, sy) = sideways ?: (alongX to alongY)
        nx = sx
        ny = sy
    }
    val on = nearest(nx, ny)
    return nx.coerceIn(on.x.toDouble(), on.right - 1.0) to ny.coerceIn(on.y.toDouble(), on.bottom - 1.0)
}
