package dev.caulis.xwayland

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** X's stacking order kept in step with the desktop with as few requests as possible. */
class XStackTest {

    private fun stack(vararg windows: Int) = XStack().apply { windows.forEach(::created) }

    @Test
    fun `nothing to do when the order is right`() {
        val stack = stack(1, 2, 3, 4)
        assertEquals(emptyList(), stack.moves(listOf(1, 3, 4)))
    }

    @Test
    fun `raising one window moves only that window`() {
        val stack = stack(1, 2, 3, 4)
        assertEquals(listOf(XStack.Move(2, 4, above = true)), stack.moves(listOf(1, 3, 4, 2)))
        assertEquals(listOf(1, 3, 4, 2), stack.windows)
    }

    @Test
    fun `lowering one window to the bottom`() {
        val stack = stack(1, 2, 3)
        assertEquals(listOf(XStack.Move(3, 1, above = false)), stack.moves(listOf(3, 1, 2)))
        assertEquals(listOf(3, 1, 2), stack.windows)
    }

    @Test
    fun `X's own reports keep the order`() {
        val stack = stack(1, 2, 3)
        stack.placedAbove(1, 3)                 // a program raised itself
        stack.placedAbove(3, XConnection.NONE)  // to the bottom
        stack.destroyed(2)
        assertEquals(listOf(3, 1), stack.windows)
    }

    @Test
    fun `any order is reached, and with the fewest moves`() {
        val random = Random(7)
        repeat(500) {
            val count = random.nextInt(2, 12)
            val stack = stack(*IntArray(count) { it + 1 })
            val others = (1..count).shuffled(random).take(random.nextInt(2, count + 1))
            val moves = stack.moves(others)
            assertEquals(others, stack.windows.filter { it in others }, "reached")
            // Fewest: the windows not moved are a longest run already in order.
            val positions = others.map { it } // they started in id order
            val longest = longestIncreasingLength(positions)
            assertEquals(others.size - longest, moves.size, "moves for $others")
        }
    }

    private fun longestIncreasingLength(values: List<Int>): Int {
        val best = IntArray(values.size) { 1 }
        for (i in values.indices) for (j in 0 until i) if (values[j] < values[i]) best[i] = maxOf(best[i], best[j] + 1)
        return best.maxOrNull() ?: 0
    }
}
