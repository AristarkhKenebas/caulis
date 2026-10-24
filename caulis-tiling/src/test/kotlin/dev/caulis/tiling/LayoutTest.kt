package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTest {

    private class W(override val title: String) : LayoutWindow {
        override val appId = ""
        override val minWidth = 0
        override val minHeight = 0
        override val maxWidth = 0
        override val maxHeight = 0
    }

    private val screen = Rect(0, 0, 1001, 600)
    private fun windows(n: Int) = (1..n).map { W("w$it") }

    @Test
    fun `splitting never loses or overlaps a pixel`() {
        for (total in listOf(1000, 1001, 1919, 7)) {
            for (count in 1..7) {
                val parts = Split.sizes(total, count, gap = 3)
                val covered = parts.sumOf { it.second } + 3 * (count - 1)
                assertEquals(maxOf(total, count + 3 * (count - 1)).coerceAtMost(maxOf(total, covered)), covered, "total=$total count=$count")
                parts.zipWithNext { a, b -> assertEquals(a.first + a.second + 3, b.first) }
            }
        }
    }

    @Test
    fun `master stack - one big window left, the rest stacked right`() {
        val rects = MasterStack(Gaps(inner = 10, outer = 0)).arrange(windows(3), screen, 0)
        assertEquals(3, rects.size)
        assertEquals(0, rects[0].x)
        assertEquals(rects[0].right + 10, rects[1].x)
        assertEquals(rects[1].x, rects[2].x)
        assertEquals(600, rects[1].height + 10 + rects[2].height, "the stack fills the height exactly")
        assertEquals(1001, rects[0].width + 10 + rects[1].width, "and the columns the width")
    }

    @Test
    fun `dwindle halves the remaining space`() {
        val rects = Dwindle(Gaps(0, 0)).arrange(windows(3), screen, 0)
        assertEquals(Rect(0, 0, 500, 600), rects[0])
        assertEquals(Rect(500, 0, 501, 300), rects[1]) // the odd pixel goes to the second half
        assertEquals(Rect(500, 300, 501, 300), rects[2])
    }

    @Test
    fun `one window fills the area minus the outer gap`() {
        for (layout in listOf(MasterStack(Gaps(4, 8)), Dwindle(Gaps(4, 8)))) {
            assertEquals(listOf(Rect(8, 8, 985, 584)), layout.arrange(windows(1), screen, 0), layout.name)
        }
    }

    @Test
    fun `scrolling columns keep the focused column in view`() {
        val model = ScrollingColumns(Gaps(0, 0)).createModel()
        val all = windows(5)
        val first = model.arrange(all, screen, all[0])
        assertEquals(0, first[0].x, "no empty space before the first column")
        val last = model.arrange(all, screen, all[4])
        assertEquals(1001, last[4].right, "no empty space after the last column")
        assertTrue(last[0].right <= 0, "the first column scrolled out of view")
    }

    @Test
    fun `floating places nothing`() {
        assertTrue(Floating.arrange(windows(3), screen, 0).isEmpty())
    }
}
