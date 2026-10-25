package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Gaps: each edge on its own, and only sensible sizes. */
class GapsTest {

    @Test
    fun `each edge on its own, the rest as outer`() {
        val gaps = Gaps(inner = 8, outer = 12, top = 40)
        assertEquals(Rect(12, 40, 1000 - 24, 800 - 52), gaps.inset(Rect(0, 0, 1000, 800)))
        val one = Columns(gaps).arrange(listOf(window()), Rect(0, 0, 1000, 800), 0).single()
        assertEquals(Rect(12, 40, 976, 748), one)
    }

    @Test
    fun `no negative gaps, none wider than a screen`() {
        assertEquals("the inner gap is 0 to 500, not -4", assertFailsWith<IllegalArgumentException> { Gaps(inner = -4) }.message)
        assertEquals("the left gap is 0 to 500, not 1200", assertFailsWith<IllegalArgumentException> { Gaps(left = 1200) }.message)
        Gaps(0, 0) // windows touching each other and the edges: fine
    }

    private fun window() = object : LayoutWindow {
        override val title = "w"
        override val appId = "w"
        override val minWidth = 0
        override val minHeight = 0
        override val maxWidth = 0
        override val maxHeight = 0
    }
}
