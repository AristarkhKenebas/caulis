package dev.caulis.render

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
}
