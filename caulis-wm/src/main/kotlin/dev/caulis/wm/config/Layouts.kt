package dev.caulis.wm.config

import dev.caulis.log.InternalCaulisApi
import dev.caulis.tiling.BinaryTree
import dev.caulis.tiling.Canvas
import dev.caulis.tiling.CenteredMaster
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.Grid
import dev.caulis.tiling.Insertion
import dev.caulis.tiling.Layout
import dev.caulis.tiling.ManualTiling
import dev.caulis.tiling.MasterLayout
import dev.caulis.tiling.NewWindow
import dev.caulis.tiling.MasterStrips
import dev.caulis.tiling.Monocle
import dev.caulis.tiling.Rows
import dev.caulis.tiling.ScrollingColumns
import dev.caulis.tiling.Side
import dev.caulis.tiling.Spiral

/** The layouts a configuration file can name. */
@InternalCaulisApi
object Layouts {
    val names = listOf(
        "floating", "dwindle", "spiral", "grid", "columns", "rows", "monocle", "master-stack", "centered-master",
        "master-strips", "tree", "tree-spiral", "manual", "scrolling-columns", "canvas",
    )

    /** What else a layout may be told (each takes what fits it). */
    @InternalCaulisApi
    data class Options(
        val masters: Int = 1,
        val newWindow: NewWindow = NewWindow.STACK_END,
        val splitRatio: Double = 0.5,
        val columnWidths: List<Double> = listOf(1.0 / 3, 0.5, 2.0 / 3, 1.0),
        val newColumnWidth: Double = 0.5,
        val centerFocused: Boolean = false,
    )

    /** The layout called [name], with [gaps] and the other settings that fit it. */
    fun byName(name: String, gaps: Gaps = Gaps(), ratio: Double? = null, side: Side = Side.LEFT, options: Options = Options()): () -> Layout {
        val key = name.lowercase().replace('_', '-').replace(' ', '-')
        return when (key) {
            "floating" -> ({ Floating })
            "dwindle" -> ({ Dwindle(gaps) })
            "spiral" -> ({ Spiral(gaps) })
            "grid" -> ({ Grid(gaps) })
            "columns" -> ({ Columns(gaps) })
            "rows" -> ({ Rows(gaps) })
            "monocle" -> ({ Monocle(gaps) })
            "master-stack" -> ({ MasterLayout(gaps, ratio ?: 0.55, side, options.masters, options.newWindow) })
            "centered-master" -> ({ CenteredMaster(gaps, ratio ?: 0.5) })
            "master-strips" -> ({ MasterStrips(gaps, ratio ?: 0.6) })
            "tree" -> ({ BinaryTree(gaps, splitRatio = options.splitRatio) })
            "tree-spiral" -> ({ BinaryTree(gaps, Insertion.SPIRAL, "Tree (spiral)", options.splitRatio) })
            "manual" -> ({ ManualTiling(gaps) })
            "scrolling-columns" -> ({ ScrollingColumns(gaps, options.columnWidths, options.newColumnWidth, options.centerFocused) })
            "canvas" -> ({ Canvas(gaps) })
            else -> throw IllegalArgumentException("\"$name\" is not a layout (there are: ${names.joinToString()})")
        }
    }
}
