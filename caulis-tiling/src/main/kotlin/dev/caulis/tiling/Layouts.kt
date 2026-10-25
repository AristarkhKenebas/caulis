@file:JvmName("Layouts")

package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.math.roundToInt

// The built-in layouts. Most are a few lines of the layout language (see tiling) — read
// them as examples for your own.

/** No tiling at all: windows stay where the user puts them. */
object Floating : Layout {
    override val name = "Floating"
    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> = emptyList()
}

/** Where the big window of a master layout goes. */
enum class Side { LEFT, RIGHT, TOP, BOTTOM }

/**
 * One big window (the oldest, the "master") and the others stacked beside it — like the
 * master layouts of Hyprland and dwm. [ratio] is the master's share of the space.
 */
fun MasterStack(gaps: Gaps = Gaps(), ratio: Double = 0.55, side: Side = Side.LEFT): Layout {
    val master = (ratio * 100).roundToInt().coerceIn(5, 95)
    val stack = 100 - master
    val name = if (side == Side.LEFT) "Master" else "Master (${side.name.lowercase()})"
    return tiling(name, gaps) {
        when (side) {
            Side.LEFT -> row { window(master); column(stack) { rest() } }
            Side.RIGHT -> row { column(stack) { rest() }; window(master) }
            Side.TOP -> column { window(master); row(stack) { rest() } }
            Side.BOTTOM -> column { row(stack) { rest() }; window(master) }
        }
    }
}

/**
 * The master in the middle, the other windows in columns left and right of it (like
 * Hyprland's centred master).
 */
fun CenteredMaster(gaps: Gaps = Gaps(), ratio: Double = 0.5): Layout {
    val master = (ratio * 100).roundToInt().coerceIn(10, 90)
    val side = (100 - master) / 2
    return tiling("Centered", gaps) {
        row {
            column(side) { share(2, 2) }
            window(master)
            column(side) { share(1, 2) }
        }
    }
}

/**
 * The master on the left; the others on the right as thin strips with only their title
 * bars showing, the focused one open (like i3's stacked containers).
 */
fun MasterStrips(gaps: Gaps = Gaps(), ratio: Double = 0.6, thin: Int = 40): Layout {
    val master = (ratio * 100).roundToInt().coerceIn(5, 95)
    return tiling("Strips", gaps) {
        row { window(master); column(100 - master) { accordion(thin = thin) } }
    }
}

/** Every new window takes half of the previous one's space (like Hyprland's dwindle). */
fun Dwindle(gaps: Gaps = Gaps()): Layout = tiling("Dwindle", gaps) { dwindle() }

/** Windows in a spiral: left, top, right, bottom, inwards (like bspwm's spiral). */
fun Spiral(gaps: Gaps = Gaps()): Layout = tiling("Spiral", gaps) { spiral() }

/** All windows in a grid, as square as possible. */
fun Grid(gaps: Gaps = Gaps()): Layout = tiling("Grid", gaps) { grid() }

/** All windows side by side in equal columns. */
fun Columns(gaps: Gaps = Gaps()): Layout = tiling("Columns", gaps) { rest() }

/** All windows on top of each other in equal rows. */
fun Rows(gaps: Gaps = Gaps()): Layout = tiling("Rows", gaps) { column { rest() } }

/** Every window fills the screen; the focused one is on top. */
fun Monocle(gaps: Gaps = Gaps()): Layout = tiling("Monocle", gaps) { monocle() }

/** Every built-in layout once, with default settings (e.g. for a "next layout" key). */
fun builtInLayouts(gaps: Gaps = Gaps()): List<Layout> = listOf(
    Floating, MasterStack(gaps), Dwindle(gaps), Spiral(gaps), Grid(gaps), MasterStrips(gaps),
    CenteredMaster(gaps), Columns(gaps), Rows(gaps), Monocle(gaps),
    MasterStack(gaps, side = Side.RIGHT), MasterStack(gaps, side = Side.TOP), MasterStack(gaps, side = Side.BOTTOM),
    // These remember things (a tree, columns, a canvas):
    BinaryTree(gaps), BspSpiral(gaps), ManualTiling(gaps), ScrollingColumns(gaps), Canvas(gaps),
)

