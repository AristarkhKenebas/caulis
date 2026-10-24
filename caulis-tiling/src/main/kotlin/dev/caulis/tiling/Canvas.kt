package dev.caulis.tiling

import dev.caulis.geometry.Rect

/**
 * One big canvas, as in newm: windows lie on an endless grid of cells, and the screen shows
 * a part of it ([columns] × [rows] cells). Nothing is squeezed — new windows go into free
 * cells next to the focused one; you move the view over the canvas ([TilingCommand.Pan]),
 * move windows by a cell ([TilingCommand.Move]) and make them bigger or smaller by a cell
 * ([TilingCommand.Resize]). Focusing a window anywhere on the canvas brings it into view
 * (that is how a dock or Alt+Tab shows a window that is far away).
 *
 * Each workspace has its own canvas; use one workspace for "one canvas only".
 * New windows take [windowColumns] × [windowRows] cells.
 */
class Canvas(
    private val gaps: Gaps = Gaps(),
    private val columns: Int = 2,
    private val rows: Int = 2,
    private val windowColumns: Int = 1,
    private val windowRows: Int = 2,
) : RememberingLayout("Canvas") {
    init {
        require(columns > 0 && rows > 0 && windowColumns > 0 && windowRows > 0) { "Canvas sizes must be positive" }
    }

    override fun createModel(): TilingModel = CanvasModel(gaps, columns, rows, windowColumns, windowRows)
}

private class CanvasModel(
    private val gaps: Gaps,
    private val columns: Int,
    private val rows: Int,
    private val windowColumns: Int,
    private val windowRows: Int,
) : TilingModel {

    /** A window's place on the canvas, in cells. */
    private data class Cells(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun overlaps(other: Cells) = x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height
        fun moved(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)
    }

    private val places = LinkedHashMap<LayoutWindow, Cells>()

    // Windows that are away for now (minimized, floating, elsewhere): their cells stay theirs.
    private val parked = HashMap<LayoutWindow, Cells>()
    private var viewX = 0 // the top left cell on screen
    private var viewY = 0

    // The view follows the focused window when the focus changes or the window is moved,
    // but not when you pan the view yourself.
    private var lastFocused: LayoutWindow? = null
    private var follow = true

    // Size of a cell on screen (from the last arrangement), for PanBy.
    private var cellWidth = 1.0
    private var cellHeight = 1.0

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> {
        // Windows that went away are parked with their cells; returning ones get them back.
        val present = windows.toSet()
        for ((window, cells) in places.filterKeys { it !in present }) parked[window] = cells
        places.keys.retainAll(present)
        for (window in windows) parked.remove(window)?.let { places[window] = it }
        // New windows: free cells next to the focused one (several at once side by side).
        var near = focused
        for (window in windows) {
            if (window in places) continue
            places[window] = freePlace(near = places[near])
            near = window
        }

        // Bring the focused window into view (just enough), unless the user panned away.
        if (focused !== lastFocused) follow = true
        lastFocused = focused
        if (follow) places[focused]?.let { cells ->
            if (cells.x < viewX) viewX = cells.x
            if (cells.x + cells.width > viewX + columns) viewX = cells.x + cells.width - columns
            if (cells.y < viewY) viewY = cells.y
            if (cells.y + cells.height > viewY + rows) viewY = cells.y + cells.height - rows
        }

        val inner = inset(area, gaps.outer)
        cellWidth = (inner.width + gaps.inner).toDouble() / columns
        cellHeight = (inner.height + gaps.inner).toDouble() / rows
        // Where cell edges are on screen; works for cells far off screen too, and the cells
        // on screen fill it exactly (no lost pixels).
        fun edgeX(cell: Int) = inner.x + Math.floorDiv((cell - viewX).toLong() * (inner.width + gaps.inner), columns.toLong()).toInt()
        fun edgeY(cell: Int) = inner.y + Math.floorDiv((cell - viewY).toLong() * (inner.height + gaps.inner), rows.toLong()).toInt()
        return windows.map { window ->
            val c = places.getValue(window)
            val x = edgeX(c.x)
            val y = edgeY(c.y)
            Rect(x, y, (edgeX(c.x + c.width) - gaps.inner - x).coerceAtLeast(1), (edgeY(c.y + c.height) - gaps.inner - y).coerceAtLeast(1))
        }
    }

    // A free spot for a new window: right of [near] if possible, else the nearest free one.
    private fun freePlace(near: Cells?): Cells {
        val start = near?.let { Cells(it.x + it.width, it.y, windowColumns, windowRows) } ?: Cells(viewX, viewY, windowColumns, windowRows)
        if (isFree(start)) return start
        // Look around the start in growing rings, nearest first.
        for (radius in 1..64) {
            val candidates = (-radius..radius).flatMap { dx -> listOf(start.moved(dx, -radius), start.moved(dx, radius)) } +
                (-radius + 1 until radius).flatMap { dy -> listOf(start.moved(-radius, dy), start.moved(radius, dy)) }
            candidates.sortedBy { Math.abs(it.x - start.x) + Math.abs(it.y - start.y) }.firstOrNull(::isFree)?.let { return it }
        }
        return start // a crowded canvas: overlap rather than fail
    }

    private fun isFree(cells: Cells, except: LayoutWindow? = null) =
        places.none { (window, other) -> window !== except && other.overlaps(cells) } &&
            parked.values.none { it.overlaps(cells) }

    override fun forget(window: LayoutWindow) {
        places.remove(window)
        parked.remove(window) // its cells are free for others now
    }

    override fun command(command: TilingCommand, focused: LayoutWindow?): Boolean = when (command) {
        is TilingCommand.Pan -> {
            viewX += command.direction.dx
            viewY += command.direction.dy
            follow = false
            true
        }
        is TilingCommand.PanBy -> {
            // To the nearest whole cell.
            viewX += Math.round(command.dx / cellWidth).toInt()
            viewY += Math.round(command.dy / cellHeight).toInt()
            follow = false
            true
        }
        is TilingCommand.Move -> {
            val window = focused
            val cells = window?.let(places::get)
            if (cells == null) {
                false
            } else {
                val target = cells.moved(command.direction.dx, command.direction.dy)
                // Someone already there trades places with it.
                val occupant = places.entries.firstOrNull { (other, c) -> other !== window && c.overlaps(target) }
                if (occupant != null) places[occupant.key] = occupant.value.copy(x = cells.x, y = cells.y)
                places[window] = target
                follow = true
                true
            }
        }
        is TilingCommand.Resize -> {
            val window = focused
            val cells = window?.let(places::get)
            if (cells == null) {
                false
            } else {
                val step = if (command.amount >= 0) 1 else -1
                val resized = when (command.direction) {
                    Direction.RIGHT -> cells.copy(width = cells.width + step)
                    Direction.DOWN -> cells.copy(height = cells.height + step)
                    Direction.LEFT -> cells.copy(x = cells.x - step, width = cells.width + step)
                    Direction.UP -> cells.copy(y = cells.y - step, height = cells.height + step)
                }
                if (resized.width < 1 || resized.height < 1) {
                    false
                } else {
                    places[window] = resized
                    follow = true
                    true
                }
            }
        }
        else -> false
    }
}
