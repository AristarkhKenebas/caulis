package dev.caulis.core.layout

import dev.caulis.render.Rect

/**
 * Decides where tiled windows go. A layout gets the windows (in their tiling order, oldest
 * first) and the free area, and returns one rectangle per window, in the same order.
 *
 * That is the whole contract, so a layout is easy to write — in Kotlin, Java, or later in
 * any scripting language:
 *
 * ```
 * object Columns : Layout {
 *     override val name = "Columns"
 *     override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int) =
 *         Split.evenly(area, windows.size, gap = 8, vertical = false)
 * }
 * ```
 *
 * Rectangles may lie outside the area (scrolling layouts); those windows are simply not
 * on screen. Layouts must not keep state about windows: everything they need is passed in,
 * so they can be swapped at any moment.
 */
interface Layout {
    /** Shown to the user, e.g. "Dwindle". */
    val name: String

    /** One rectangle per window in [windows]; [focused] is the index of the focused one (-1 = none). */
    fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect>
}

/** What a layout may know about a window. */
interface LayoutWindow {
    val title: String
    val appId: String

    /** Size limits the program asked for (0 = no limit). */
    val minWidth: Int
    val minHeight: Int
    val maxWidth: Int
    val maxHeight: Int
}

/** Space around and between tiled windows, in pixels. */
data class Gaps(val inner: Int = 8, val outer: Int = 8)

/** Integer splitting without lost pixels: the parts and gaps always add up exactly. */
object Split {

    /**
     * Splits [total] pixels into [count] parts with [gap] pixels between them. Returns
     * (start, size) pairs; the leftover pixels of the division go one each to the first
     * parts, so nothing is lost and nothing overlaps.
     */
    fun sizes(total: Int, count: Int, gap: Int): List<Pair<Int, Int>> {
        if (count <= 0) return emptyList()
        val usable = (total - gap * (count - 1)).coerceAtLeast(count)
        val base = usable / count
        val extra = usable % count
        var position = 0
        return (0 until count).map { i ->
            val size = base + if (i < extra) 1 else 0
            (position to size).also { position += size + gap }
        }
    }

    /** [count] rectangles side by side ([vertical] = false) or on top of each other, filling [area]. */
    fun evenly(area: Rect, count: Int, gap: Int, vertical: Boolean): List<Rect> =
        sizes(if (vertical) area.height else area.width, count, gap).map { (start, size) ->
            if (vertical) Rect(area.x, area.y + start, area.width, size) else Rect(area.x + start, area.y, size, area.height)
        }

    /** [area] split in two at [fraction] (0..1) with [gap] in between: (first, second). */
    fun two(area: Rect, fraction: Double, gap: Int, vertical: Boolean): Pair<Rect, Rect> {
        val total = if (vertical) area.height else area.width
        val first = ((total - gap) * fraction).toInt().coerceIn(1, (total - gap - 1).coerceAtLeast(1))
        val second = (total - gap - first).coerceAtLeast(1)
        return if (vertical) {
            Rect(area.x, area.y, area.width, first) to Rect(area.x, area.y + first + gap, area.width, second)
        } else {
            Rect(area.x, area.y, first, area.height) to Rect(area.x + first + gap, area.y, second, area.height)
        }
    }
}
