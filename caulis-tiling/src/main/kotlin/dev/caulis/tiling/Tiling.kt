package dev.caulis.tiling

import dev.caulis.geometry.Rect

/**
 * Describes a layout with building blocks instead of arithmetic:
 *
 * ```
 * val Master = tiling("Master") {
 *     row {
 *         window(weight = 55)            // the first window: 55% of the width
 *         column(weight = 45) { rest() } // all the others, on top of each other
 *     }
 * }
 * ```
 *
 * - [TilingScope.row] puts its parts side by side, [TilingScope.column] on top of each other.
 * - [TilingScope.window] takes one window; [TilingScope.rest] all windows not placed yet.
 * - `weight` is a part's share of its row or column (55 and 45 = 55% and 45%).
 * - Parts that get no windows disappear, so there are never empty holes: with one window,
 *   the master above fills the whole screen.
 * - Space is divided without losing a pixel, and the gaps are added for you.
 *
 * The description runs for every arrangement, with [TilingScope.count] (how many windows)
 * and [TilingScope.focused] known — so plain `if` works: `if (count > 4) grid() else rest()`.
 */
fun tiling(name: String, gaps: Gaps = Gaps(), describe: TilingScope.() -> Unit): Layout = DescribedLayout(name, gaps, describe)

/** The building blocks of [tiling]. */
class TilingScope internal constructor(
    private val container: Node.Box,
    /** How many windows are being arranged. */
    val count: Int,
    /** Which of them has the focus (0 = the first, -1 = none). */
    val focused: Int,
) {
    /** Parts side by side (left to right). */
    fun row(weight: Int = 1, build: TilingScope.() -> Unit) = box(horizontal = true, weight, build)

    /** Parts on top of each other (top to bottom). */
    fun column(weight: Int = 1, build: TilingScope.() -> Unit) = box(horizontal = false, weight, build)

    /** One window (the next one in order: the first `window()` gets the oldest window). */
    fun window(weight: Int = 1) = add(Node.Group(weight, Take.One, Style.EVEN))

    /** All windows not placed elsewhere, side by side in a row or stacked in a column. */
    fun rest(weight: Int = 1) = add(Node.Group(weight, Take.Rest, Style.EVEN))

    /**
     * Part [part] of [of] equal shares of the remaining windows — e.g. `share(1, 2)` and
     * `share(2, 2)` put half of the others on each side of a centred master.
     */
    fun share(part: Int, of: Int, weight: Int = 1) {
        require(of > 0 && part in 1..of) { "share($part, $of): part must be between 1 and $of" }
        add(Node.Group(weight, Take.Share(part, of), Style.EVEN))
    }

    /** The remaining windows in a grid (as square as possible). */
    fun grid(weight: Int = 1) = add(Node.Group(weight, Take.Rest, Style.GRID))

    /** The remaining windows, each taking half of what is left (like Hyprland's dwindle). */
    fun dwindle(weight: Int = 1) = add(Node.Group(weight, Take.Rest, Style.DWINDLE))

    /** The remaining windows in a spiral: left, top, right, bottom, ... (like bspwm). */
    fun spiral(weight: Int = 1) = add(Node.Group(weight, Take.Rest, Style.SPIRAL))

    /**
     * The remaining windows stacked, the focused one big and the others [thin] pixels high
     * — thin strips showing their title bars (like i3's stacked containers).
     */
    fun accordion(weight: Int = 1, thin: Int = 40) = add(Node.Group(weight, Take.Rest, Style.ACCORDION, thin))

    /** The remaining windows all in the same place, the focused one on top. */
    fun monocle(weight: Int = 1) = add(Node.Group(weight, Take.Rest, Style.MONOCLE))

    private fun box(horizontal: Boolean, weight: Int, build: TilingScope.() -> Unit) {
        val box = Node.Box(horizontal, weight)
        TilingScope(box, count, focused).build()
        add(box)
    }

    private fun add(node: Node) {
        require(node.weight > 0) { "weight must be positive, got ${node.weight}" }
        container.children += node
    }
}

// --- how a description becomes rectangles ------------------------------------------------

internal sealed class Node(val weight: Int) {
    class Box(val horizontal: Boolean, weight: Int) : Node(weight) {
        val children = ArrayList<Node>()
    }

    class Group(weight: Int, val take: Take, val style: Style, val thin: Int = 0) : Node(weight) {
        /** The windows (their indices) this group got. */
        val windows = ArrayList<Int>()
    }
}

internal sealed class Take {
    object One : Take()
    object Rest : Take()
    class Share(val part: Int, val of: Int) : Take()
}

internal enum class Style { EVEN, GRID, DWINDLE, SPIRAL, ACCORDION, MONOCLE }

private class DescribedLayout(override val name: String, private val gaps: Gaps, private val describe: TilingScope.() -> Unit) : Layout {

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> {
        if (windows.isEmpty()) return emptyList()
        val root = Node.Box(horizontal = true, weight = 1)
        TilingScope(root, windows.size, focused).describe()
        val groups = groupsOf(root)
        assign(groups, windows.size)
        val inner = gaps.inset(area)
        val result = arrayOfNulls<Rect>(windows.size)
        place(root, inner, focused, result, Minimums(windows))
        // Windows the description had no room for share the whole area (none are lost).
        return result.map { it ?: inner }
    }

    // Windows are handed out in order: first to every window() block, then to the blocks
    // that take several (rest, share, grid, ...), each in the order they were written.
    private fun assign(groups: List<Node.Group>, count: Int) {
        var next = 0
        for (group in groups) if (group.take == Take.One && next < count) group.windows += next++
        val remaining = count - next
        var restTaken = false
        for (group in groups) {
            when (val take = group.take) {
                Take.One -> {}
                Take.Rest -> if (!restTaken) {
                    restTaken = true
                    while (next < count) group.windows += next++
                }
                is Take.Share -> {
                    // Equal shares of what was left after the single windows; earlier shares get the extra ones.
                    val sizes = (0 until take.of).map { remaining / take.of + if (it < remaining % take.of) 1 else 0 }
                    repeat(sizes[take.part - 1]) { if (next < count) group.windows += next++ }
                }
            }
        }
    }

    private fun groupsOf(node: Node): List<Node.Group> = when (node) {
        is Node.Group -> listOf(node)
        is Node.Box -> node.children.flatMap(::groupsOf)
    }

    private fun windowsIn(node: Node): Int = when (node) {
        is Node.Group -> node.windows.size
        is Node.Box -> node.children.sumOf(::windowsIn)
    }

    // The smallest the windows may be (what their programs asked for), and so the parts holding them.
    private inner class Minimums(private val windows: List<LayoutWindow>) {
        // Window [i]'s minimum width ([horizontal]) or height.
        fun of(i: Int, horizontal: Boolean): Int = (if (horizontal) windows[i].minWidth else windows[i].minHeight).coerceAtLeast(1) // (a pixel at least)

        // Windows [indices] one after the other along the split ([along]), else beside each other.
        fun ofAll(indices: List<Int>, horizontal: Boolean, along: Boolean): Int =
            if (indices.isEmpty()) 0 else if (along) indices.sumOf { of(it, horizontal) } + gaps.inner * (indices.size - 1) else indices.maxOf { of(it, horizontal) }

        // The smallest [node] may be across [horizontal] (its windows' minimums added up where
        // they lie one after the other); [splitHorizontal]: the direction the box around it splits in.
        fun of(node: Node, horizontal: Boolean, splitHorizontal: Boolean): Int = when (node) {
            is Node.Box -> {
                val parts = node.children.filter { windowsIn(it) > 0 }
                val each = parts.map { of(it, horizontal, node.horizontal) }
                if (each.isEmpty()) 0 else if (node.horizontal == horizontal) each.sum() + gaps.inner * (each.size - 1) else each.max()
            }
            // (An even group lies along its box; the others' windows are taken as side by side at most.)
            is Node.Group -> ofAll(node.windows, horizontal, along = node.style == Style.EVEN && splitHorizontal == horizontal)
        }
    }

    // Divides [rect] among the parts that got windows (empty parts take no space), none
    // smaller than its windows' minimums while they fit.
    private fun place(node: Node, rect: Rect, focused: Int, result: Array<Rect?>, minimums: Minimums, horizontal: Boolean = true) {
        when (node) {
            is Node.Box -> {
                val parts = node.children.filter { windowsIn(it) > 0 }
                val least = parts.map { minimums.of(it, node.horizontal, node.horizontal) }
                val sizes = Split.weighted(if (node.horizontal) rect.width else rect.height, parts.map { it.weight }, gaps.inner, least)
                for ((part, slot) in parts.zip(sizes)) {
                    val (start, size) = slot
                    val partRect = if (node.horizontal) Rect(rect.x + start, rect.y, size, rect.height) else Rect(rect.x, rect.y + start, rect.width, size)
                    place(part, partRect, focused, result, minimums, node.horizontal)
                }
            }
            is Node.Group -> {
                val rects = arrangeGroup(node, rect, focused, horizontal, minimums)
                node.windows.zip(rects).forEach { (window, r) -> result[window] = r }
            }
        }
    }

    private fun arrangeGroup(group: Node.Group, rect: Rect, focused: Int, horizontal: Boolean, minimums: Minimums): List<Rect> {
        val n = group.windows.size
        val w = group.windows
        return when (group.style) {
            Style.EVEN -> Split.evenly(rect, n, gaps.inner, vertical = !horizontal, w.map { minimums.of(it, horizontal) })
            Style.MONOCLE -> List(n) { rect }
            Style.GRID -> grid(rect, w, minimums)
            Style.DWINDLE -> dwindle(rect, w, minimums)
            Style.SPIRAL -> spiral(rect, w, minimums)
            Style.ACCORDION -> accordion(rect, group.windows.indexOf(focused).coerceAtLeast(0), n, group.thin)
        }
    }

    private fun grid(rect: Rect, w: List<Int>, minimums: Minimums): List<Rect> {
        val n = w.size
        val columns = Math.ceil(Math.sqrt(n.toDouble())).toInt()
        val rows = (n + columns - 1) / columns
        val inRows = (0 until rows).map { row -> w.subList(row * columns, minOf(n, (row + 1) * columns)) }
        return Split.evenly(rect, rows, gaps.inner, vertical = true, inRows.map { minimums.ofAll(it, horizontal = false, along = false) })
            .flatMapIndexed { row, rowRect ->
                Split.evenly(rowRect, inRows[row].size, gaps.inner, vertical = false, inRows[row].map { minimums.of(it, horizontal = true) })
            }
    }

    // Window [i] of [w] takes its part of [rest] (split [vertical]ly), the windows after it the other part.
    private fun twoFor(rest: Rect, w: List<Int>, i: Int, vertical: Boolean, windowFirst: Boolean, minimums: Minimums): Pair<Rect, Rect> {
        val horizontal = !vertical
        val own = minimums.of(w[i], horizontal)
        val others = minimums.ofAll(w.subList(i + 1, w.size), horizontal, along = false)
        return Split.two(rest, 0.5, gaps.inner, vertical, if (windowFirst) own else others, if (windowFirst) others else own)
    }

    // True if [rest] split [vertical]ly has room for window [i]'s minimum and the others'.
    private fun fits(rest: Rect, w: List<Int>, i: Int, vertical: Boolean, minimums: Minimums): Boolean {
        val horizontal = !vertical
        val needed = minimums.of(w[i], horizontal) + minimums.ofAll(w.subList(i + 1, w.size), horizontal, along = false) + gaps.inner
        return needed <= if (vertical) rest.height else rest.width
    }

    private fun dwindle(rect: Rect, w: List<Int>, minimums: Minimums): List<Rect> {
        val n = w.size
        val result = ArrayList<Rect>(n)
        var rest = rect
        repeat(n) { i ->
            if (i == n - 1) {
                result += rest
            } else {
                // Split the long way — unless only the other way gives the windows their minimums.
                val long = rest.height > rest.width
                val vertical = if (!fits(rest, w, i, long, minimums) && fits(rest, w, i, !long, minimums)) !long else long
                val (first, second) = twoFor(rest, w, i, vertical, windowFirst = true, minimums)
                result += first
                rest = second
            }
        }
        return result
    }

    // Each window takes half of what is left, turning clockwise: left, top, right, bottom.
    private fun spiral(rect: Rect, w: List<Int>, minimums: Minimums): List<Rect> {
        val n = w.size
        val result = ArrayList<Rect>(n)
        var rest = rect
        repeat(n) { i ->
            if (i == n - 1) {
                result += rest
                return@repeat
            }
            val vertical = i % 2 == 1
            val windowTakesFirst = i % 4 < 2
            val (first, second) = twoFor(rest, w, i, vertical, windowTakesFirst, minimums)
            result += if (windowTakesFirst) first else second
            rest = if (windowTakesFirst) second else first
        }
        return result
    }

    private fun accordion(rect: Rect, open: Int, n: Int, thin: Int): List<Rect> {
        val strips = (n - 1) * (thin + gaps.inner)
        if (n == 1 || strips >= rect.height - thin) return Split.evenly(rect, n, gaps.inner, vertical = true)
        val big = rect.height - strips
        var y = rect.y
        return List(n) { i ->
            val height = if (i == open) big else thin
            Rect(rect.x, y, rect.width, height).also { y += height + gaps.inner }
        }
    }
}
