package dev.caulis.xwayland

/**
 * The stacking order of X's top-level windows (bottom to top), as X reports it: windows are
 * created on top, and every ConfigureNotify says which window one is now right above.
 *
 * [moves] finds the fewest restacking requests that bring some windows into a wanted order:
 * those already in that order among themselves (the longest such run) stay where they are,
 * each other one is put right above its wanted neighbour below (or, below the lowest that
 * stays, right below its neighbour above). Raising one window moves only that window.
 */
internal class XStack {

    /** One restacking request: put [window] right above (or below) [sibling]. */
    data class Move(val window: Int, val sibling: Int, val above: Boolean)

    private val order = ArrayList<Int>()

    /** The order now, bottom to top. */
    val windows: List<Int> get() = order

    fun created(window: Int) {
        order.remove(window)
        order += window
    }

    fun destroyed(window: Int) {
        order.remove(window)
    }

    /** X put [window] right above [sibling] (or at the bottom: [XConnection.NONE]). */
    fun placedAbove(window: Int, sibling: Int) {
        order.remove(window)
        val below = if (sibling == XConnection.NONE) -1 else order.indexOf(sibling)
        if (sibling != XConnection.NONE && below < 0) order += window else order.add(below + 1, window)
    }

    /**
     * The requests that bring [wanted] (bottom to top; windows this stack knows) into that
     * order among themselves; the stack is updated as if X carried them out.
     */
    fun moves(wanted: List<Int>): List<Move> {
        val known = wanted.filter { it in order }
        if (known.size < 2) return emptyList()
        val positions = known.map(order::indexOf)
        val staying = longestIncreasing(positions).map { known[it] }.toSet()
        val result = ArrayList<Move>()
        val first = known.indexOfFirst { it in staying }
        // Those wanted below the lowest staying one: each right below the next, downwards.
        for (i in first - 1 downTo 0) {
            result += Move(known[i], known[i + 1], above = false)
            order.remove(known[i])
            order.add(order.indexOf(known[i + 1]), known[i])
        }
        // The others: each right above its wanted neighbour below (final by then), upwards.
        for (i in first + 1 until known.size) {
            if (known[i] in staying) continue
            result += Move(known[i], known[i - 1], above = true)
            placedAbove(known[i], known[i - 1])
        }
        return result
    }

    // Indexes into [values] of a longest strictly increasing subsequence (patience sorting).
    private fun longestIncreasing(values: List<Int>): List<Int> {
        val tails = ArrayList<Int>() // index of the smallest tail of each length
        val previous = IntArray(values.size) { -1 }
        for (i in values.indices) {
            var low = 0
            var high = tails.size
            while (low < high) {
                val middle = (low + high) / 2
                if (values[tails[middle]] < values[i]) low = middle + 1 else high = middle
            }
            if (low > 0) previous[i] = tails[low - 1]
            if (low == tails.size) tails += i else tails[low] = i
        }
        val result = ArrayList<Int>()
        var at = tails.lastOrNull() ?: return result
        while (at >= 0) {
            result += at
            at = previous[at]
        }
        return result.asReversed()
    }
}
