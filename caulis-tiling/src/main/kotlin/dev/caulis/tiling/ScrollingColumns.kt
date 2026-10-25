package dev.caulis.tiling

import dev.caulis.geometry.Rect

/**
 * Columns on an endless strip that scrolls sideways, as in niri: a new window opens as a
 * new column right of the focused one, so nothing else gets squeezed. A column can hold
 * several windows on top of each other ([TilingCommand.Consume] moves a window into the
 * neighbouring column or out into its own). The view scrolls only as far as needed to show
 * the focused column.
 *
 * [widths] are the column widths [TilingCommand.CycleWidth] goes through, as shares of the
 * screen; new columns get [newWidth]. [centerFocused]: the focused column is always in the
 * middle (else the view moves only as far as needed).
 *
 * Messages ([TilingCommand.Message]): `width 0.5` / `width +0.1` (the focused column's
 * width), `center` (the focused column to the middle, once).
 */
class ScrollingColumns @JvmOverloads constructor(
    private val gaps: Gaps = Gaps(),
    private val widths: List<Double> = listOf(1.0 / 3, 0.5, 2.0 / 3, 1.0),
    private val newWidth: Double = 0.5,
    private val centerFocused: Boolean = false,
) : RememberingLayout("Columns (scrolling)") {
    override fun createModel(): TilingModel = ColumnsModel(gaps, widths, newWidth, centerFocused)
}

private class ColumnsModel(private val gaps: Gaps, private val widths: List<Double>, private val newWidth: Double, private val centerFocused: Boolean = false) : TilingModel {

    // The focused column goes to the middle at the next arrangement (once, or always with centerFocused).
    private var centerOnce = false

    private class Column(var width: Double) {
        val windows = ArrayList<LayoutWindow>()
    }

    private val columns = ArrayList<Column>()
    private var scroll = 0 // how far the strip is scrolled, in pixels

    // The view follows the focused column, unless it was moved by hand (until the focus changes).
    private var lastFocused: LayoutWindow? = null
    private var follow = true

    private fun columnOf(window: LayoutWindow?): Column? = columns.firstOrNull { window in it.windows }

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> {
        val present = windows.toSet()
        for (column in columns) column.windows.retainAll(present)
        columns.removeAll { it.windows.isEmpty() }
        // New windows: new columns right of the focused one (several at once in order).
        var after = focused
        for (window in windows) {
            if (columnOf(window) != null) continue
            val at = columnOf(after)?.let { columns.indexOf(it) + 1 } ?: columns.size
            columns.add(at, Column(newWidth).also { it.windows += window })
            after = window
        }

        val inner = gaps.inset(area)
        val pixelWidths = columns.map { ((inner.width + gaps.inner) * it.width).toInt() - gaps.inner }
        val starts = pixelWidths.runningFold(0) { x, w -> x + w + gaps.inner }
        val stripWidth = (starts.last() - gaps.inner).coerceAtLeast(0)

        // Scroll just enough to show the focused column (niri: "on overflow").
        if (focused !== lastFocused) follow = true
        lastFocused = focused
        if (follow) columnOf(focused)?.let { column ->
            val i = columns.indexOf(column)
            val left = starts[i]
            val right = left + pixelWidths[i]
            if (centerFocused || centerOnce) {
                scroll = left + pixelWidths[i] / 2 - inner.width / 2
                centerOnce = false
            }
            if (left < scroll) scroll = left
            if (right > scroll + inner.width) scroll = right - inner.width
        }
        scroll = scroll.coerceIn(0, (stripWidth - inner.width).coerceAtLeast(0))

        val rects = HashMap<LayoutWindow, Rect>()
        for ((i, column) in columns.withIndex()) {
            val columnRect = Rect(inner.x + starts[i] - scroll, inner.y, pixelWidths[i].coerceAtLeast(1), inner.height)
            Split.evenly(columnRect, column.windows.size, gaps.inner, vertical = true)
                .forEachIndexed { j, r -> rects[column.windows[j]] = r }
        }
        return windows.map { rects.getValue(it) }
    }

    override fun command(command: TilingCommand, focused: LayoutWindow?): Boolean {
        if (command is TilingCommand.PanBy) {
            scroll += command.dx
            follow = false
            return true
        }
        val window = focused ?: return false
        val column = columnOf(window) ?: return false
        val index = columns.indexOf(column)
        return when (command) {
            is TilingCommand.Move -> when (command.direction) {
                Direction.LEFT, Direction.RIGHT -> {
                    val other = index + command.direction.dx
                    if (other !in columns.indices) return false
                    columns[index] = columns[other].also { columns[other] = column }
                    true
                }
                Direction.UP, Direction.DOWN -> {
                    val i = column.windows.indexOf(window)
                    val other = i + command.direction.dy
                    if (other !in column.windows.indices) return false
                    column.windows[i] = column.windows[other].also { column.windows[other] = window }
                    true
                }
            }
            is TilingCommand.Consume -> {
                if (!command.direction.horizontal) return false
                if (column.windows.size > 1) {
                    // Out of a shared column: into its own, on that side.
                    column.windows -= window
                    val at = if (command.direction == Direction.LEFT) index else index + 1
                    columns.add(at, Column(column.width).also { it.windows += window })
                } else {
                    // Into the neighbouring column.
                    val neighbour = columns.getOrNull(index + command.direction.dx) ?: return false
                    neighbour.windows += window
                    columns.removeAt(index)
                }
                true
            }
            TilingCommand.CycleWidth -> {
                val next = widths.firstOrNull { it > column.width + 0.01 } ?: widths.first()
                column.width = next
                true
            }
            is TilingCommand.Resize -> {
                if (!command.direction.horizontal) return false
                column.width = (column.width + command.amount).coerceIn(0.1, 1.0)
                true
            }
            is TilingCommand.Message -> when (command.name) {
                "width" -> true.also { column.width = command.number(column.width).coerceIn(0.1, 1.0) }
                "center" -> true.also {
                    centerOnce = true
                    follow = true
                }
                else -> false
            }
            else -> false
        }
    }
}
