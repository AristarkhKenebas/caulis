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
 * master layouts of Hyprland and dwm. [ratio] is the master's share of the space; see
 * [MasterLayout] for more masters, where new windows go, and the messages it understands.
 */
@JvmOverloads
fun MasterStack(gaps: Gaps = Gaps(), ratio: Double = 0.55, side: Side = Side.LEFT): Layout = MasterLayout(gaps, ratio, side)

/**
 * The master in the middle, the other windows in columns left and right of it (like
 * Hyprland's centred master).
 */
@JvmOverloads
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
@JvmOverloads
fun MasterStrips(gaps: Gaps = Gaps(), ratio: Double = 0.6, thin: Int = 40): Layout {
    val master = (ratio * 100).roundToInt().coerceIn(5, 95)
    return tiling("Strips", gaps) {
        row { window(master); column(100 - master) { accordion(thin = thin) } }
    }
}

/** Every new window takes half of the previous one's space (like Hyprland's dwindle). */
@JvmOverloads
fun Dwindle(gaps: Gaps = Gaps()): Layout = tiling("Dwindle", gaps) { dwindle() }

/** Windows in a spiral: left, top, right, bottom, inwards (like bspwm's spiral). */
@JvmOverloads
fun Spiral(gaps: Gaps = Gaps()): Layout = tiling("Spiral", gaps) { spiral() }

/** All windows in a grid, as square as possible. */
@JvmOverloads
fun Grid(gaps: Gaps = Gaps()): Layout = tiling("Grid", gaps) { grid() }

/** All windows side by side in equal columns. */
@JvmOverloads
fun Columns(gaps: Gaps = Gaps()): Layout = tiling("Columns", gaps) { rest() }

/** All windows on top of each other in equal rows. */
@JvmOverloads
fun Rows(gaps: Gaps = Gaps()): Layout = tiling("Rows", gaps) { column { rest() } }

/** Every window fills the screen; the focused one is on top. */
@JvmOverloads
fun Monocle(gaps: Gaps = Gaps()): Layout = tiling("Monocle", gaps) { monocle() }

/** Every built-in layout once, with default settings (e.g. for a "next layout" key). */
@JvmOverloads
fun builtInLayouts(gaps: Gaps = Gaps()): List<Layout> = listOf(
    Floating, MasterStack(gaps), Dwindle(gaps), Spiral(gaps), Grid(gaps), MasterStrips(gaps),
    CenteredMaster(gaps), Columns(gaps), Rows(gaps), Monocle(gaps),
    MasterStack(gaps, side = Side.RIGHT), MasterStack(gaps, side = Side.TOP), MasterStack(gaps, side = Side.BOTTOM),
    // These remember things (a tree, columns, a canvas):
    BinaryTree(gaps), BspSpiral(gaps), ManualTiling(gaps), ScrollingColumns(gaps), Canvas(gaps),
)

