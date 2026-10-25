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

    private class W(override val title: String) : LayoutWindow {
        override val appId = ""
        override val minWidth = 0
        override val minHeight = 0
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
    fun `wrong settings are refused with a plain message`() {
        assertFailsWith<IllegalArgumentException> { Split.weighted(100, listOf(0, 0), 0) }
        assertFailsWith<IllegalArgumentException> { ScrollingColumns(widths = emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScrollingColumns(newWidth = 0.0) }
        assertFailsWith<IllegalArgumentException> { TilingCommand.Message.of("ratio NaN").number(0.5) }
        assertFailsWith<IllegalArgumentException> { MasterLayout(ratio = 2.0) }
    }
}
