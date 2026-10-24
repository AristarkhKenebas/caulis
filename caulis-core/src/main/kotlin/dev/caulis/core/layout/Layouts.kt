package dev.caulis.core.layout

import dev.caulis.render.Rect

/** No tiling at all: windows stay where the user puts them. */
object Floating : Layout {
    override val name = "Floating"
    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> = emptyList()
}

/**
 * One big window on the left (the oldest, the "master"), the others stacked on the right
 * (like Hyprland's master layout). [ratio] is the master's share of the width.
 */
class MasterStack(private val gaps: Gaps = Gaps(), private val ratio: Double = 0.55) : Layout {
    override val name = "Master"

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> {
        val inner = inset(area, gaps.outer)
        if (windows.size <= 1) return windows.map { inner }
        val (master, stack) = Split.two(inner, ratio, gaps.inner, vertical = false)
        return listOf(master) + Split.evenly(stack, windows.size - 1, gaps.inner, vertical = true)
    }
}

/**
 * Every new window takes half of the previous one's space, alternating the direction along
 * the longer side (like Hyprland's default "dwindle" layout).
 */
class Dwindle(private val gaps: Gaps = Gaps()) : Layout {
    override val name = "Dwindle"

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> {
        val result = ArrayList<Rect>(windows.size)
        var rest = inset(area, gaps.outer)
        for (i in windows.indices) {
            if (i == windows.lastIndex) {
                result += rest
            } else {
                val (first, second) = Split.two(rest, 0.5, gaps.inner, vertical = rest.height > rest.width)
                result += first
                rest = second
            }
        }
        return result
    }
}

/**
 * Windows as columns on an endless strip that scrolls sideways (like newm or niri). New
 * windows add columns instead of squeezing the others; the focused column is kept in view.
 * [columnWidth] is a column's share of the screen width.
 */
class Scrolling(private val gaps: Gaps = Gaps(), private val columnWidth: Double = 0.5) : Layout {
    override val name = "Scrolling"

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> {
        if (windows.isEmpty()) return emptyList()
        val inner = inset(area, gaps.outer)
        val width = ((inner.width + gaps.inner) * columnWidth).toInt() - gaps.inner
        val step = width + gaps.inner
        val stripWidth = windows.size * step - gaps.inner
        // Scroll so the focused column is centred, without empty space before the first
        // column or after the last one.
        val center = (focused.coerceAtLeast(0)) * step + width / 2
        val scroll = (center - inner.width / 2).coerceIn(0, (stripWidth - inner.width).coerceAtLeast(0))
        return windows.indices.map { i -> Rect(inner.x + i * step - scroll, inner.y, width, inner.height) }
    }
}

// [area] made smaller by [margin] on every side (never below 1 pixel).
internal fun inset(area: Rect, margin: Int): Rect =
    Rect(area.x + margin, area.y + margin, (area.width - 2 * margin).coerceAtLeast(1), (area.height - 2 * margin).coerceAtLeast(1))
