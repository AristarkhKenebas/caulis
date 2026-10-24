package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TilingLanguageTest {

    private class W(override val title: String) : LayoutWindow {
        override val appId = ""
        override val minWidth = 0
        override val minHeight = 0
        override val maxWidth = 0
        override val maxHeight = 0
    }

    private val screen = Rect(0, 0, 1000, 600)
    private val noGaps = Gaps(0, 0)
    private fun windows(n: Int) = (1..n).map { W("w$it") }

    @Test
    fun `empty parts disappear, so one window fills everything`() {
        val master = tiling("M", noGaps) { row { window(60); column(40) { rest() } } }
        assertEquals(listOf(screen), master.arrange(windows(1), screen, 0))
        val two = master.arrange(windows(2), screen, 0)
        assertEquals(Rect(0, 0, 600, 600), two[0])
        assertEquals(Rect(600, 0, 400, 600), two[1])
    }

    @Test
    fun `plain if works inside a description`() {
        val adaptive = tiling("A", noGaps) { if (count > 3) grid() else rest() }
        assertEquals(3, adaptive.arrange(windows(3), screen, 0).map { it.y }.count { it == 0 }, "three side by side")
        assertEquals(2, adaptive.arrange(windows(4), screen, 0).map { it.y }.distinct().size, "four in a 2x2 grid")
    }

    @Test
    fun `spiral turns clockwise`() {
        val r = Spiral(noGaps).arrange(windows(4), screen, 0)
        assertEquals(Rect(0, 0, 500, 600), r[0], "left half")
        assertEquals(Rect(500, 0, 500, 300), r[1], "then the top")
        assertTrue(r[2].x > r[3].x, "then the right, the last one left of it")
    }

    @Test
    fun `strips - the focused window is open, the others thin`() {
        val strips = MasterStrips(noGaps, ratio = 0.5, thin = 40)
        val r = strips.arrange(windows(4), screen, focused = 2)
        assertEquals(Rect(0, 0, 500, 600), r[0])
        assertEquals(40, r[1].height)
        assertEquals(600 - 80, r[2].height, "the focused one gets the rest")
        assertEquals(40, r[3].height)
    }

    @Test
    fun `a centred master has windows on both sides`() {
        val r = CenteredMaster(noGaps).arrange(windows(5), screen, 0)
        assertTrue(r[0].x > 0 && r[0].right < 1000, "the master is in the middle: ${r[0]}")
        assertTrue(r.drop(1).any { it.right <= r[0].x } && r.drop(1).any { it.x >= r[0].right })
    }

    @Test
    fun `every built-in tiles without gaps, overlaps or lost pixels`() {
        val gaps = Gaps(inner = 7, outer = 5)
        // (Monocle overlaps on purpose; scrolling and canvas layouts go beyond the screen.)
        val tiling = builtInLayouts(gaps).filter { it.name !in setOf("Floating", "Monocle", "Columns (scrolling)", "Canvas") }
        for (layout in tiling) {
            for (n in 1..12) {
                for (focused in listOf(0, n - 1)) {
                    val rects = layout.arrange(windows(n), screen, focused)
                    val where = "${layout.name}, $n windows, focus $focused"
                    assertEquals(n, rects.size, where)
                    val inner = Rect(5, 5, 990, 590)
                    for (r in rects) assertTrue(inner.contains(r) && !r.isEmpty, "$where: $r is outside $inner")
                    for (i in rects.indices) for (j in i + 1 until rects.size) {
                        assertTrue(rects[i].intersect(rects[j]) == null, "$where: ${rects[i]} overlaps ${rects[j]}")
                    }
                    // Whatever is not covered by windows is gaps only (at most the inner gap wide).
                    val covered = rects.sumOf { it.width.toLong() * it.height }
                    assertTrue(covered > inner.width.toLong() * inner.height * 0.85 || n > 6, "$where: too much empty space")
                }
            }
        }
    }
}
