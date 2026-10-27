package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every layout under random windows, areas and commands: one rectangle per window, never empty,
 * and on a canvas never one window over another. (A fixed seed: a failure comes back the same.)
 */
class LayoutFuzzTest {

    private class W(override val title: String, override val minWidth: Int = 0, override val minHeight: Int = 0) : LayoutWindow {
        override val appId = ""
        override val maxWidth = 0
        override val maxHeight = 0
        override fun toString() = title
    }

    private val commands = listOf<(Random) -> TilingCommand>(
        { r -> TilingCommand.Move(Direction.entries.random(r)) },
        { r -> TilingCommand.Resize(Direction.entries.random(r), if (r.nextBoolean()) 0.1 else -0.1) },
        { r -> TilingCommand.Preselect(Direction.entries.random(r)) },
        { r -> TilingCommand.Consume(Direction.entries.random(r)) },
        { _ -> TilingCommand.Rotate },
        { _ -> TilingCommand.Balance },
        { _ -> TilingCommand.CycleWidth },
        { r -> TilingCommand.Pan(Direction.entries.random(r)) },
        { r -> TilingCommand.Message.of(listOf("ratio +0.1", "ratio -0.2", "masters +1", "toggle-split", "swap-split", "width 0.3", "center", "cycle next").random(r)) },
    )

    @Test
    fun `every layout gives each window a place, whatever happens`() {
        val random = Random(20261025)
        for (layout in builtInLayouts(Gaps(4, 8))) {
            val model = layout.createModel()
            val windows = ArrayList<W>()
            repeat(300) { step ->
                when (random.nextInt(4)) {
                    0 -> if (windows.size < 40) windows += W("w$step")
                    1 -> if (windows.isNotEmpty()) windows.removeAt(random.nextInt(windows.size)).let(model::forget)
                    else -> windows.randomOrNull(random)?.let { focused -> model.command(commands.random(random)(random), focused) }
                }
                val area = Rect(0, 0, 1 + random.nextInt(2000), 1 + random.nextInt(1200))
                val focused = windows.randomOrNull(random)
                val rects = model.arrange(windows, area, focused)
                if (layout === Floating) return@repeat
                assertEquals(windows.size, rects.size, "${layout.name}: a place per window")
                for (rect in rects) assertTrue(rect.width >= 1 && rect.height >= 1, "${layout.name}: $rect is empty (area $area)")
                if (layout.name == "Canvas") {
                    for (i in rects.indices) for (j in i + 1 until rects.size) {
                        assertTrue(rects[i].intersect(rects[j]) == null, "${layout.name}: ${windows[i]} and ${windows[j]} overlap: ${rects[i]} ${rects[j]}")
                    }
                }
            }
        }
    }

    @Test
    fun `a window's minimum size is kept when there is room, and nothing overlaps`() {
        // A laptop screen; one program (Steam, Discord) wants 940 wide, another 500 high.
        val area = Rect(0, 0, 1366, 768)
        val layouts = listOf(
            MasterStack(), MasterLayout(side = Side.RIGHT), Dwindle(), Spiral(), Grid(), CenteredMaster(), Columns(), Rows(),
            BinaryTree(), BspSpiral(), ManualTiling(), ScrollingColumns(),
        )
        for (layout in layouts) {
            for (count in 2..3) for (wide in 0 until count) {
                val windows = List(count) { i -> if (i == wide) W("wide", minWidth = 940) else W("w$i") }
                val model = layout.createModel()
                val rects = model.arrange(windows, area, windows.first())
                val name = "${layout.name} with $count windows, the ${wide + 1}. wide"
                assertTrue(rects[wide].width >= 940, "$name: ${rects[wide]} is narrower than 940")
                for (i in rects.indices) for (j in i + 1 until rects.size) {
                    assertTrue(rects[i].intersect(rects[j]) == null, "$name: ${rects[i]} and ${rects[j]} overlap")
                }
            }
            val windows = listOf(W("a"), W("tall", minHeight = 500))
            val rects = layout.createModel().arrange(windows, area, windows.first())
            if (layout.name != "Columns" && layout.name != "Scrolling") assertTrue(rects[1].height >= 500, "${layout.name}: ${rects[1]} is lower than 500")
        }
        // Minimums that cannot all fit: shared as usual (each still gets a place).
        val rects = Split.weighted(1000, listOf(1, 1), 10, listOf(800, 800))
        assertEquals(listOf(0 to 495, 505 to 495), rects)
    }

    @Test
    fun `the master layout turns clockwise and balances back to its own share`() {
        val model = MasterLayout(Gaps(0, 0), ratio = 0.7).createModel()
        val windows = listOf(W("master"), W("other"))
        val area = Rect(0, 0, 1000, 1000)
        assertEquals(Rect(0, 0, 700, 1000), model.arrange(windows, area, windows[0])[0])
        model.command(TilingCommand.Rotate, windows[0])
        assertEquals(Rect(0, 0, 1000, 700), model.arrange(windows, area, windows[0])[0], "a quarter turn: the master on top")
        model.command(TilingCommand.Message.of("ratio 0.3"), windows[0])
        model.command(TilingCommand.Balance, windows[0])
        assertEquals(Rect(0, 0, 1000, 700), model.arrange(windows, area, windows[0])[0], "back to 0.7")
    }

    @Test
    fun `wrong settings are refused with a plain message`() {
        assertFailsWith<IllegalArgumentException> { Split.weighted(100, listOf(0, 0), 0) }
        assertFailsWith<IllegalArgumentException> { ScrollingColumns(widths = emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScrollingColumns(newWidth = 0.0) }
        assertFailsWith<IllegalArgumentException> { TilingCommand.Message.of("ratio NaN").number(0.5) }
        assertFailsWith<IllegalArgumentException> { MasterLayout(ratio = 2.0) }
    }
}
