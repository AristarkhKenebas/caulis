package dev.caulis.geometry

/** A rectangle in whole pixels. */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** The common part of both rectangles, or null if they do not overlap. */
    fun intersect(other: Rect): Rect? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > left && b > top) Rect(left, top, r - left, b - top) else null
    }

    /** True if [other] lies completely inside this rectangle. */
    fun contains(other: Rect): Boolean =
        other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    /** The smallest rectangle that contains both. */
    fun union(other: Rect): Rect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        return Rect(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
    }

    /** What is left of this rectangle without [other]: up to four rectangles, apart from each other. */
    fun minus(other: Rect): List<Rect> {
        val common = intersect(other) ?: return listOf(this)
        return listOf(
            Rect(x, y, width, common.y - y),                                     // above
            Rect(x, common.bottom, width, bottom - common.bottom),               // below
            Rect(x, common.y, common.x - x, common.height),                      // left
            Rect(common.right, common.y, right - common.right, common.height),   // right
        ).filter { !it.isEmpty }
    }

    fun translate(dx: Int, dy: Int) = Rect(x + dx, y + dy, width, height)
}
