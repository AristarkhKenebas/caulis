package dev.caulis.core

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/** The input thread's early cursor works out the pointer's place as the desktop does (monitors touching). */
class MovedOverTest {
    private val left = Rect(0, 0, 1920, 1080)
    private val right = Rect(1920, 0, 1280, 720)
    private val both = listOf(left, right)

    @Test
    fun `within a monitor it simply moves`() {
        assertEquals(110.0 to 95.0, movedOver(both, 100.0, 100.0, 10.0, -5.0))
    }

    @Test
    fun `onto the monitor beside`() {
        assertEquals(1925.0 to 100.0, movedOver(both, 1915.0, 100.0, 10.0, 0.0))
    }

    @Test
    fun `stops at an edge with no monitor beyond`() {
        assertEquals(0.0 to 1079.0, movedOver(both, 5.0, 1075.0, -20.0, 30.0))
    }

    @Test
    fun `slides along where the monitor beside is shorter`() {
        // From the left one's lower part to the right: the right one ends at 720, it stays on the left.
        assertEquals(1919.0 to 900.0, movedOver(both, 1910.0, 900.0, 30.0, 0.0))
    }
}
