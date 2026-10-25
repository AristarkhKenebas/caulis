package dev.caulis.tiling

import dev.caulis.geometry.Rect

/** How a tree layout chooses where a new window goes (when nothing was preselected). */
enum class Insertion {
    /** Split the focused window along its longer side (bspwm's default). */
    LONGEST_SIDE,

    /** Turn like a spiral: right, below, left, above, ... (bspwm's spiral). */
    SPIRAL,

    /** Alternate between side by side and on top of each other. */
    ALTERNATE,
}

/**
 * Windows in a tree of splits: every new window splits the focused one in two (like
 * bspwm; for i3-style tiling see [ManualTiling]). The tree is remembered, so you can
 * preselect where the next window goes, swap windows, move the split lines, rotate and
 * balance. A new split gives the new window 1 - [splitRatio] of the space.
 *
 * Messages ([TilingCommand.Message]), as Hyprland's dwindle has them: `toggle-split` (the
 * focused window's split turns: side by side ↔ on top of each other), `swap-split` (its two
 * sides change places), `ratio 0.6` / `ratio +0.05` (its split line).
 */
class BinaryTree @JvmOverloads constructor(
    private val gaps: Gaps = Gaps(),
    private val insertion: Insertion = Insertion.LONGEST_SIDE,
    name: String = "Tree",
    private val splitRatio: Double = 0.5,
) : RememberingLayout(name) {
    init {
        require(splitRatio in 0.1..0.9) { "a split ratio is 0.1 to 0.9, not $splitRatio" }
    }

    override fun createModel(): TilingModel = TreeModel(gaps, insertion, splitRatio)
}

/**
 * Tiling as in i3: windows live in containers that are either side by side or on top of
 * each other. A new window joins the focused window's container (all get equal shares),
 * or — after [TilingCommand.SplitNext] in the other direction — the focused window and the
 * new one get a container of their own.
 */
class ManualTiling(private val gaps: Gaps = Gaps()) : RememberingLayout("Manual") {
    override fun createModel(): TilingModel = ContainerModel(gaps)
}

private class ContainerModel(private val gaps: Gaps) : TilingModel {

    private sealed class Node {
        var parent: Container? = null
        var weight = 1.0
    }

    private class Leaf(var window: LayoutWindow) : Node()

    private class Container(var sideBySide: Boolean) : Node() {
        val children = ArrayList<Node>()

        fun add(index: Int, node: Node) {
            children.add(index, node)
            node.parent = this
        }
    }

    private var root = Container(sideBySide = true)
    private val leaves = LinkedHashMap<LayoutWindow, Leaf>()
    private var lastRects: Map<LayoutWindow, Rect> = emptyMap()
    private var nextSideBySide: Boolean? = null

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> {
        for (gone in leaves.keys - windows.toSet()) remove(gone)
        var near = focused
        for (window in windows) {
            if (window in leaves) continue
            insert(window, near)
            near = window
        }
        val rects = HashMap<LayoutWindow, Rect>()
        place(root, gaps.inset(area), rects)
        lastRects = rects
        return windows.map { rects[it] ?: gaps.inset(area) }
    }

    private fun place(node: Node, rect: Rect, out: MutableMap<LayoutWindow, Rect>) {
        when (node) {
            is Leaf -> out[node.window] = rect
            is Container -> {
                if (node.children.isEmpty()) return
                val total = if (node.sideBySide) rect.width else rect.height
                // Weights in thousandths keep resized proportions; equal by default.
                val sizes = Split.weighted(total, node.children.map { (it.weight * 1000).toInt().coerceAtLeast(1) }, gaps.inner)
                for ((child, slot) in node.children.zip(sizes)) {
                    val (start, size) = slot
                    val childRect = if (node.sideBySide) Rect(rect.x + start, rect.y, size, rect.height) else Rect(rect.x, rect.y + start, rect.width, size)
                    place(child, childRect, out)
                }
            }
        }
    }

    private fun insert(window: LayoutWindow, near: LayoutWindow?) {
        val leaf = Leaf(window)
        leaves[window] = leaf
        val target = leaves[near]?.takeIf { it !== leaf } ?: leaves.values.lastOrNull { it !== leaf }
        if (target == null) {
            root.add(root.children.size, leaf)
            return
        }
        val container = target.parent ?: root
        val wanted = nextSideBySide ?: container.sideBySide
        nextSideBySide = null
        val index = container.children.indexOf(target)
        if (wanted == container.sideBySide || container.children.size == 1) {
            // Join the container: everyone gets an equal share again.
            container.sideBySide = wanted
            container.add(index + 1, leaf)
            container.children.forEach { it.weight = 1.0 }
        } else {
            // The other direction: the focused window and the new one get their own container.
            val inner = Container(wanted)
            inner.weight = target.weight
            container.children[index] = inner
            inner.parent = container
            target.weight = 1.0
            inner.add(0, target)
            inner.add(1, leaf)
        }
    }

    private fun remove(window: LayoutWindow) {
        val leaf = leaves.remove(window) ?: return
        val container = leaf.parent ?: return
        container.children.remove(leaf)
        // A container left with one child is replaced by that child.
        val parent = container.parent
        if (container.children.size == 1 && parent != null) {
            val only = container.children.single()
            only.weight = container.weight
            parent.children[parent.children.indexOf(container)] = only
            only.parent = parent
        }
    }

    override fun command(command: TilingCommand, focused: LayoutWindow?): Boolean {
        val leaf = leaves[focused]
        return when (command) {
            is TilingCommand.SplitNext -> {
                nextSideBySide = command.sideBySide
                false
            }
            is TilingCommand.Move -> {
                val from = focused?.let(lastRects::get) ?: return false
                val other = lastRects.entries.filter { (w, r) ->
                    w !== focused && when (command.direction) {
                        Direction.LEFT -> r.right <= from.x
                        Direction.RIGHT -> r.x >= from.right
                        Direction.UP -> r.bottom <= from.y
                        Direction.DOWN -> r.y >= from.bottom
                    }
                }.minByOrNull { (_, r) -> distance(from, r) }?.key?.let(leaves::get) ?: return false
                val window = leaf!!.window
                leaf.window = other.window
                other.window = window
                leaves[leaf.window] = leaf
                leaves[other.window] = other
                true
            }
            is TilingCommand.Resize -> {
                // Grow inside the nearest container of the matching direction.
                var child: Node = leaf ?: return false
                var container = child.parent
                while (container != null && container.sideBySide != command.direction.horizontal) {
                    child = container
                    container = container.parent
                }
                if (container == null || container.children.size < 2) return false
                child.weight = (child.weight + command.amount * container.children.size * 2).coerceIn(0.2, 5.0)
                true
            }
            TilingCommand.Rotate -> {
                fun turn(node: Node) {
                    if (node is Container) {
                        node.sideBySide = !node.sideBySide
                        node.children.forEach(::turn)
                    }
                }
                turn(root)
                true
            }
            TilingCommand.Balance -> {
                fun even(node: Node) {
                    node.weight = 1.0
                    if (node is Container) node.children.forEach(::even)
                }
                even(root)
                true
            }
            else -> false
        }
    }
}

/** bspwm's spiral, remembered: windows keep their places when others open or close. */
fun BspSpiral(gaps: Gaps = Gaps()): Layout = BinaryTree(gaps, Insertion.SPIRAL, "Tree (spiral)")

private class TreeModel(private val gaps: Gaps, private val insertion: Insertion, private val splitRatio: Double = 0.5) : TilingModel {

    private sealed class Node {
        var parent: Fork? = null
    }

    private class Leaf(var window: LayoutWindow) : Node()

    private class Fork(var sideBySide: Boolean, var ratio: Double, first: Node, second: Node) : Node() {
        var first: Node = first
            set(value) {
                field = value
                value.parent = this
            }
        var second: Node = second
            set(value) {
                field = value
                value.parent = this
            }

        init {
            first.parent = this
            second.parent = this
        }
    }

    private var root: Node? = null
    private val leaves = LinkedHashMap<LayoutWindow, Leaf>()
    private var lastRects: Map<LayoutWindow, Rect> = emptyMap()
    private var preselected: Direction? = null

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> {
        // Bring the tree up to date: gone windows out, new ones in next to the focused one.
        for (gone in leaves.keys - windows.toSet()) remove(gone)
        // Several new windows at once go in as if opened one after another: each next to
        // the previous new one, with the sizes known so far.
        var near = focused
        for (window in windows) {
            if (window in leaves) continue
            insert(window, near = near, area = area)
            near = window
            lastRects = placeAll(area)
        }
        val rects = placeAll(area)
        lastRects = rects
        return windows.map { rects[it] ?: gaps.inset(area) }
    }

    private fun placeAll(area: Rect): Map<LayoutWindow, Rect> =
        HashMap<LayoutWindow, Rect>().also { rects -> root?.let { place(it, gaps.inset(area), rects) } }

    private fun place(node: Node, rect: Rect, out: MutableMap<LayoutWindow, Rect>) {
        when (node) {
            is Leaf -> out[node.window] = rect
            is Fork -> {
                val (first, second) = Split.two(rect, node.ratio, gaps.inner, vertical = !node.sideBySide)
                place(node.first, first, out)
                place(node.second, second, out)
            }
        }
    }

    private fun insert(window: LayoutWindow, near: LayoutWindow?, area: Rect) {
        val leaf = Leaf(window)
        leaves[window] = leaf
        val current = root
        if (current == null) {
            root = leaf
            return
        }
        // The window to split: the focused one, else the newest one.
        val target = leaves[near]?.takeIf { it !== leaf } ?: leaves.values.lastOrNull { it !== leaf } ?: return
        val targetRect = lastRects[target.window] ?: gaps.inset(area)
        val chosen = preselected
        preselected = null
        val (sideBySide, newFirst) = when {
            chosen != null -> chosen.horizontal to (chosen == Direction.LEFT || chosen == Direction.UP)
            insertion == Insertion.LONGEST_SIDE -> (targetRect.width >= targetRect.height) to false
            insertion == Insertion.ALTERNATE -> !(target.parent?.sideBySide ?: false) to false
            else -> depth(target).let { d -> (d % 2 == 0) to (d % 4 >= 2) } // spiral
        }
        val parent = target.parent
        val split = if (newFirst) Fork(sideBySide, 1 - splitRatio, leaf, target) else Fork(sideBySide, splitRatio, target, leaf)
        replace(parent, target, split)
    }

    private fun remove(window: LayoutWindow) {
        val leaf = leaves.remove(window) ?: return
        val parent = leaf.parent
        if (parent == null) {
            root = null
            return
        }
        val sibling = if (parent.first === leaf) parent.second else parent.first
        replace(parent.parent, parent, sibling)
    }

    // Puts [new] where [old] was under [parent] (or at the root).
    private fun replace(parent: Fork?, old: Node, new: Node) {
        when {
            parent == null -> {
                root = new
                new.parent = null
            }
            parent.first === old -> parent.first = new
            else -> parent.second = new
        }
    }

    private fun depth(node: Node): Int {
        var depth = 0
        var current = node.parent
        while (current != null) {
            depth++
            current = current.parent
        }
        return depth
    }

    override fun command(command: TilingCommand, focused: LayoutWindow?): Boolean {
        val leaf = leaves[focused]
        return when (command) {
            is TilingCommand.Preselect -> {
                preselected = if (preselected == command.direction) null else command.direction
                false
            }
            is TilingCommand.SplitNext -> {
                preselected = if (command.sideBySide) Direction.RIGHT else Direction.DOWN
                false
            }
            is TilingCommand.Move -> {
                val other = leaf?.let { neighbour(it.window, command.direction) }?.let(leaves::get) ?: return false
                val window = leaf.window
                leaf.window = other.window
                other.window = window
                leaves[leaf.window] = leaf
                leaves[other.window] = other
                true
            }
            is TilingCommand.Resize -> leaf?.let { resize(it, command.direction, command.amount) } ?: false
            TilingCommand.Rotate -> {
                splits().forEach { it.sideBySide = !it.sideBySide }
                true
            }
            TilingCommand.Balance -> {
                splits().forEach { it.ratio = 0.5 }
                true
            }
            is TilingCommand.Message -> {
                val split = leaf?.parent ?: return false
                when (command.name) {
                    "toggle-split" -> true.also { split.sideBySide = !split.sideBySide }
                    "swap-split" -> true.also {
                        val first = split.first
                        split.first = split.second
                        split.second = first
                        split.ratio = 1 - split.ratio
                    }
                    "ratio" -> {
                        // The focused window's own share when it is the first side, else the other's.
                        val now = if (split.first === leaf) split.ratio else 1 - split.ratio
                        val wanted = command.number(now).coerceIn(0.05, 0.95)
                        true.also { split.ratio = if (split.first === leaf) wanted else 1 - wanted }
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    // Moves the nearest split line on that side of the window.
    private fun resize(leaf: Leaf, direction: Direction, amount: Double): Boolean {
        var child: Node = leaf
        var split = leaf.parent
        while (split != null) {
            if (split.sideBySide == direction.horizontal) {
                val growsTowardsSecond = direction == Direction.RIGHT || direction == Direction.DOWN
                val inFirst = split.first === child
                if (inFirst == growsTowardsSecond) {
                    split.ratio = (split.ratio + if (inFirst) amount else -amount).coerceIn(0.1, 0.9)
                    return true
                }
            }
            child = split
            split = split.parent
        }
        return false
    }

    private fun splits(): List<Fork> {
        val result = ArrayList<Fork>()
        fun walk(node: Node?) {
            if (node is Fork) {
                result += node
                walk(node.first)
                walk(node.second)
            }
        }
        walk(root)
        return result
    }

    // The closest window on that side, by the last arrangement.
    private fun neighbour(window: LayoutWindow, direction: Direction): LayoutWindow? {
        val from = lastRects[window] ?: return null
        return lastRects.filterKeys { it !== window }.entries.filter { (_, r) ->
            when (direction) {
                Direction.LEFT -> r.right <= from.x
                Direction.RIGHT -> r.x >= from.right
                Direction.UP -> r.bottom <= from.y
                Direction.DOWN -> r.y >= from.bottom
            }
        }.minByOrNull { (_, r) -> distance(from, r) }?.key
    }
}

internal fun distance(a: Rect, b: Rect): Long {
    val dx = (b.x + b.width / 2) - (a.x + a.width / 2)
    val dy = (b.y + b.height / 2) - (a.y + a.height / 2)
    return dx.toLong() * dx + dy.toLong() * dy
}
