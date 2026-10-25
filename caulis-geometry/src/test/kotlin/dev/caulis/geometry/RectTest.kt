package dev.caulis.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RectTest {

    @Test
    fun `intersection and union`() {
        val a = Rect(0, 0, 10, 10)
        val b = Rect(5, 5, 10, 10)
        assertEquals(Rect(5, 5, 5, 5), a.intersect(b))
        assertEquals(Rect(0, 0, 15, 15), a.union(b))
        assertNull(a.intersect(Rect(10, 0, 5, 5)), "touching edges do not overlap")
        assertEquals(b, Rect(0, 0, 0, 0).union(b), "an empty rectangle adds nothing")
    }

    @Test
    fun `what is left without another`() {
        val big = Rect(0, 0, 10, 10)
        val parts = big.minus(Rect(3, 3, 4, 4))
        assertEquals(100 - 16, parts.sumOf { it.width * it.height }, "all but the hole")
        for (a in parts) for (b in parts) if (a !== b) assertEquals(null, a.intersect(b), "apart: $a $b")
        assertEquals(listOf(big), big.minus(Rect(20, 20, 2, 2)))
        assertEquals(emptyList(), big.minus(Rect(-1, -1, 20, 20)))
    }
}
