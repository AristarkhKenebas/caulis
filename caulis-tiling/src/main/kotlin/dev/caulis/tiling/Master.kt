package dev.caulis.tiling

import dev.caulis.geometry.Rect

/** Where a new window goes in a master layout. */
enum class NewWindow {
    /** At the end of the stack (the default). */
    STACK_END,

    /** At the top of the stack. */
    STACK_TOP,

    /** It becomes the master (the master before goes to the top of the stack). */
    MASTER,
}

/**
 * The master layout of Hyprland and dwm, remembered per workspace: [masters] big windows
 * on one [side], the others stacked beside them; the master's share is [ratio]. All of it can
 * be changed while running with messages ([TilingCommand.Message]):
 *
 * ```
 * ratio 0.6 · ratio +0.05 · ratio -0.05      the masters' share
 * side left|right|top|bottom                  where the masters are
 * masters +1 · masters -1 · masters 2         how many masters
 * swap-with-master                            the focused window and the master change places
 * focus-master                                the master gets the focus
 * cycle next|prev                             every window moves one place on
 * ```
 *
 * Moving a window ([TilingCommand.Move]) swaps it with its neighbour in the order; resizing
 * ([TilingCommand.Resize]) towards the stack makes the masters bigger.
 */
class MasterLayout @JvmOverloads constructor(
    private val gaps: Gaps = Gaps(),
    private val ratio: Double = 0.55,
    private val side: Side = Side.LEFT,
    private val masters: Int = 1,
    private val newWindow: NewWindow = NewWindow.STACK_END,
) : RememberingLayout(if (side == Side.LEFT) "Master" else "Master (${side.name.lowercase()})") {
    init {
        require(ratio in 0.05..0.95) { "a master's share is 0.05 to 0.95, not $ratio" }
        require(masters >= 1) { "there is at least one master, not $masters" }
    }

    override fun createModel(): TilingModel = MasterModel(gaps, ratio, side, masters, newWindow)
}

private class MasterModel(
    private val gaps: Gaps,
    private var ratio: Double,
    private var side: Side,
    private var masters: Int,
    private val newWindow: NewWindow,
) : TilingModel {

    // The share it started with (Balance goes back to it).
    private val initialRatio = ratio

    // Masters first, then the stack.
    private val order = ArrayList<LayoutWindow>()
    private var focusWanted: LayoutWindow? = null

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> {
        order.retainAll(windows.toSet())
        for (window in windows) {
            if (window in order) continue
            when (newWindow) {
                NewWindow.STACK_END -> order += window
                NewWindow.STACK_TOP -> order.add(masters.coerceAtMost(order.size), window)
                NewWindow.MASTER -> order.add(0, window)
            }
        }
        val rects = place(gaps.inset(area))
        return windows.map { rects[it] ?: gaps.inset(area) }
    }

    private fun place(area: Rect): Map<LayoutWindow, Rect> {
        val out = HashMap<LayoutWindow, Rect>()
        if (order.isEmpty()) return out
        val count = masters.coerceAtMost(order.size)
        val top = order.subList(0, count)
        val rest = order.subList(count, order.size)
        // Masters side by side along the stack's direction: on the left, they are on top of each other.
        val vertical = side == Side.TOP || side == Side.BOTTOM
        // The smallest each window may be (what its program asked for): across the split, and along it.
        fun across(w: LayoutWindow) = (if (vertical) w.minHeight else w.minWidth).coerceAtLeast(0)
        fun along(w: LayoutWindow) = (if (vertical) w.minWidth else w.minHeight).coerceAtLeast(0)
        if (rest.isEmpty()) {
            Split.evenly(area, top.size, gaps.inner, !vertical, top.map(::along)).forEachIndexed { i, r -> out[top[i]] = r }
            return out
        }
        val masterFirst = side == Side.LEFT || side == Side.TOP
        // (The split moves so neither side is narrower than its widest window's minimum, while they fit.)
        val masterLeast = top.maxOf(::across)
        val stackLeast = rest.maxOf(::across)
        val (first, second) = Split.two(
            area, if (masterFirst) ratio else 1 - ratio, gaps.inner, vertical,
            if (masterFirst) masterLeast else stackLeast, if (masterFirst) stackLeast else masterLeast,
        )
        val (masterArea, stackArea) = if (masterFirst) first to second else second to first
        Split.evenly(masterArea, top.size, gaps.inner, !vertical, top.map(::along)).forEachIndexed { i, r -> out[top[i]] = r }
        Split.evenly(stackArea, rest.size, gaps.inner, !vertical, rest.map(::along)).forEachIndexed { i, r -> out[rest[i]] = r }
        return out
    }

    override fun command(command: TilingCommand, focused: LayoutWindow?): Boolean = when (command) {
        is TilingCommand.Move -> {
            val i = order.indexOf(focused)
            val forward = command.direction == Direction.RIGHT || command.direction == Direction.DOWN
            val j = if (forward) i + 1 else i - 1
            if (i < 0 || j !in order.indices) false else true.also { java.util.Collections.swap(order, i, j) }
        }
        is TilingCommand.Resize -> {
            // Towards the stack: the masters grow.
            val towardsStack = when (side) {
                Side.LEFT -> Direction.RIGHT
                Side.RIGHT -> Direction.LEFT
                Side.TOP -> Direction.DOWN
                Side.BOTTOM -> Direction.UP
            }
            val grow = when (command.direction) {
                towardsStack -> command.amount
                towardsStack.opposite -> -command.amount
                else -> 0.0
            }
            setRatio(ratio + grow)
        }
        // A quarter turn clockwise: left, top, right, bottom.
        TilingCommand.Rotate -> true.also { side = CLOCKWISE[(CLOCKWISE.indexOf(side) + 1) % CLOCKWISE.size] }
        // Back to the share it started with.
        TilingCommand.Balance -> setRatio(initialRatio)
        is TilingCommand.Message -> message(command, focused)
        else -> false
    }

    private fun setRatio(value: Double): Boolean {
        val new = value.coerceIn(0.05, 0.95)
        if (new == ratio) return false
        ratio = new
        return true
    }

    private fun message(message: TilingCommand.Message, focused: LayoutWindow?): Boolean = when (message.name) {
        "ratio" -> setRatio(message.number(ratio))
        "side" -> {
            val wanted = Side.entries.firstOrNull { it.name.equals(message.argument, ignoreCase = true) }
                ?: throw IllegalArgumentException("\"${message.argument}\" is not left, right, top or bottom")
            (wanted != side).also { side = wanted }
        }
        "masters" -> {
            val wanted = message.number(masters.toDouble()).toInt().coerceIn(1, 20)
            (wanted != masters).also { masters = wanted }
        }
        "swap-with-master" -> {
            val i = order.indexOf(focused)
            // The master itself: it changes places with the first of the stack.
            val j = if (i == 0) 1 else 0
            if (i < 0 || j !in order.indices) false else true.also {
                java.util.Collections.swap(order, i, j)
                focusWanted = focused
            }
        }
        "focus-master" -> false.also { focusWanted = order.firstOrNull() }
        "cycle" -> {
            if (order.size < 2) false else true.also {
                if (message.argument == "prev") order.add(order.removeAt(0)) else order.add(0, order.removeAt(order.size - 1))
                focusWanted = focused
            }
        }
        else -> false
    }

    override fun takeFocus(): LayoutWindow? = focusWanted.also { focusWanted = null }

    override fun forget(window: LayoutWindow) {
        order -= window
    }

    private companion object {
        val CLOCKWISE = listOf(Side.LEFT, Side.TOP, Side.RIGHT, Side.BOTTOM)
    }
}
