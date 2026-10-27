package dev.caulis.tiling

import dev.caulis.geometry.Rect

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

    /**
     * A fresh arrangement for one workspace. Layouts that remember things (trees, columns,
     * a canvas) return their own [TilingModel]; the others need not override this.
     */
    fun createModel(): TilingModel = StatelessModel(this)
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

/**
 * Space between tiled windows ([inner]) and between them and the edges of the screen
 * ([outer] — or each edge on its own: [top], [right], [bottom], [left]), in desktop units:
 *
 * ```
 * Gaps(inner = 8, outer = 12)
 * Gaps(inner = 8, outer = 12, top = 4)          // less at the top (under a bar)
 * Gaps(inner = 0, outer = 0)                     // windows touch, and touch the edges
 * ```
 *
 * Each is 0 to [MAX]: no gap is negative (windows would overlap) or wider than a screen.
 */
data class Gaps @JvmOverloads constructor(
    val inner: Int = 8,
    val outer: Int = 8,
    val top: Int = outer,
    val right: Int = outer,
    val bottom: Int = outer,
    val left: Int = outer,
) {
    init {
        for ((name, value) in listOf("inner" to inner, "outer" to outer, "top" to top, "right" to right, "bottom" to bottom, "left" to left)) {
            require(value in 0..MAX) { "the $name gap is 0 to $MAX, not $value" }
        }
    }

    /** [area] less the outer gaps. */
    fun inset(area: Rect): Rect =
        Rect(area.x + left, area.y + top, (area.width - left - right).coerceAtLeast(1), (area.height - top - bottom).coerceAtLeast(1))

    companion object {
        /** The widest a gap can be. */
        const val MAX = 500
    }
}

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

    /**
     * Like [sizes], but the parts get space in proportion to [weights] (e.g. 55 and 45).
     * The leftover pixels of the rounding go one each to the first parts.
     */
    fun weighted(total: Int, weights: List<Int>, gap: Int): List<Pair<Int, Int>> {
        if (weights.isEmpty()) return emptyList()
        require(weights.all { it > 0 }) { "weights are more than 0, not $weights" }
        val usable = (total - gap * (weights.size - 1)).coerceAtLeast(weights.size)
        val sum = weights.sumOf { it.toLong() }
        val sizes = weights.map { (usable * it.toLong() / sum).toInt().coerceAtLeast(1) }.toMutableList()
        var leftover = usable - sizes.sum()
        var i = 0
        while (leftover > 0) {
            sizes[i % sizes.size]++
            leftover--
            i++
        }
        var position = 0
        return sizes.map { size -> (position to size).also { position += size + gap } }
    }

    /**
     * Like [weighted], but no part is smaller than its minimum in [minimums] (0: none; e.g. a
     * window's minWidth): parts that would be get their minimum, the others share the rest by
     * weight. When the minimums together do not fit, it is as [weighted] (nothing can help).
     */
    fun weighted(total: Int, weights: List<Int>, gap: Int, minimums: List<Int>): List<Pair<Int, Int>> {
        require(minimums.size == weights.size) { "one minimum per part (${weights.size}), not ${minimums.size}" }
        val usable = (total - gap * (weights.size - 1)).coerceAtLeast(weights.size)
        if (minimums.all { it <= 1 } || minimums.sumOf { it.coerceAtLeast(1).toLong() } > usable) return weighted(total, weights, gap)
        // Parts held at their minimum; the others share what is left by weight (again until none is too small).
        val held = BooleanArray(weights.size)
        var sizes: IntArray
        while (true) {
            val free = usable - weights.indices.filter { held[it] }.sumOf { minimums[it] }
            val sum = weights.indices.filter { !held[it] }.sumOf { weights[it].toLong() }
            sizes = IntArray(weights.size) { i -> if (held[i]) minimums[i] else (free * weights[i].toLong() / sum.coerceAtLeast(1)).toInt().coerceAtLeast(1) }
            val small = weights.indices.filter { !held[it] && sizes[it] < minimums[it] }
            if (small.isEmpty()) break
            small.forEach { held[it] = true }
        }
        // The pixels the rounding left go one each to the parts not held.
        var leftover = usable - sizes.sum()
        val growing = weights.indices.filter { !held[it] }.ifEmpty { weights.indices.toList() }
        var i = 0
        while (leftover > 0) {
            sizes[growing[i % growing.size]]++
            leftover--
            i++
        }
        // (Parts made 1 pixel at least can come to more than there is: the biggest give it back.)
        while (leftover < 0) {
            val biggest = sizes.indices.filter { sizes[it] > maxOf(1, minimums[it]) }.maxByOrNull { sizes[it] } ?: break
            sizes[biggest]--
            leftover++
        }
        var position = 0
        return sizes.map { size -> (position to size).also { position += size + gap } }
    }

    /** [count] rectangles side by side ([vertical] = false) or on top of each other, filling [area]. */
    fun evenly(area: Rect, count: Int, gap: Int, vertical: Boolean): List<Rect> =
        sizes(if (vertical) area.height else area.width, count, gap).map { (start, size) ->
            if (vertical) Rect(area.x, area.y + start, area.width, size) else Rect(area.x + start, area.y, size, area.height)
        }

    /**
     * Like [evenly], but no rectangle is smaller (along the split) than its minimum in
     * [minimums] while they fit (see [weighted]).
     */
    fun evenly(area: Rect, count: Int, gap: Int, vertical: Boolean, minimums: List<Int>): List<Rect> =
        weighted(if (vertical) area.height else area.width, List(count) { 1 }, gap, minimums).map { (start, size) ->
            if (vertical) Rect(area.x, area.y + start, area.width, size) else Rect(area.x + start, area.y, size, area.height)
        }

    /** [area] split in two at [fraction] (0..1) with [gap] in between: (first, second). */
    fun two(area: Rect, fraction: Double, gap: Int, vertical: Boolean): Pair<Rect, Rect> = two(area, fraction, gap, vertical, 0, 0)

    /**
     * Like [two], but the split moves so neither part is smaller (along the split) than its
     * minimum ([minFirst], [minSecond]; 0: none) — when both fit.
     */
    fun two(area: Rect, fraction: Double, gap: Int, vertical: Boolean, minFirst: Int, minSecond: Int): Pair<Rect, Rect> {
        val total = if (vertical) area.height else area.width
        if (total < gap + 2) return area to area // too small to split: both share it (never outside)
        var first = ((total - gap) * fraction).toInt().coerceIn(1, total - gap - 1)
        val least = minFirst.coerceAtLeast(1)
        val most = total - gap - minSecond.coerceAtLeast(1)
        if (least <= most) first = first.coerceIn(least, most)
        val second = total - gap - first
        return if (vertical) {
            Rect(area.x, area.y, area.width, first) to Rect(area.x, area.y + first + gap, area.width, second)
        } else {
            Rect(area.x, area.y, first, area.height) to Rect(area.x + first + gap, area.y, second, area.height)
        }
    }
}
