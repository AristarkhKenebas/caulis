package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.wayland.WlRegion

/** A set of rectangles a client describes (e.g. which part of a surface accepts input). */
class Region internal constructor(resource: WlRegion, private val maxParts: Int = MAX_PARTS) {

    internal companion object {
        const val MAX_PARTS = 256
    }

    private val parts = ArrayList<Pair<Rect, Boolean>>() // rectangle, added (true) or subtracted

    // Too many parts were sent (a client could send millions, each costing work every frame):
    // from then on only roughly — all that was added, as one rectangle.
    private var overflowed = false

    init {
        resource.data = this
        resource.requests = object : WlRegion.Requests {
            override fun destroy(resource: WlRegion) {}

            override fun add(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                part(Rect(x, y, width, height), true)
            }

            override fun subtract(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                part(Rect(x, y, width, height), false)
            }
        }
    }

    // Never more than [maxParts] to look at; past that, all that was added as one rectangle
    // (which then counts for input only: nothing of it is taken as certainly covered).
    private fun part(rect: Rect, added: Boolean) {
        if (rect.width <= 0 || rect.height <= 0) return
        parts += rect to added
        if (parts.size > maxParts) {
            val all = parts.filter { it.second }.map { it.first }.reduceOrNull(Rect::union)
            parts.clear()
            if (all != null) parts += all to true
            overflowed = true
        }
    }

    /** A copy that stays the same when the client changes the region later. */
    fun snapshot() = Snapshot(parts.toList(), overflowed)

    class Snapshot internal constructor(private val parts: List<Pair<Rect, Boolean>>, private val rough: Boolean) {
        constructor(parts: List<Pair<Rect, Boolean>>) : this(parts, false)

        // The same region, made of the same parts? (Programs often set it anew with each picture.)
        internal fun sameAs(other: Snapshot): Boolean = rough == other.rough && parts == other.parts

        /**
         * Rectangles that are certainly inside the region: added ones that no later
         * subtraction touches. (Careful rather than exact: it may leave parts out — all of
         * them for a region of too many parts, whose opaque area is then taken as none.)
         */
        fun solidRects(): List<Rect> = solid

        // (Worked out once: it is asked every frame.)
        private val solid: List<Rect> by lazy {
            if (rough) return@lazy emptyList()
            parts.withIndex().filter { (index, part) ->
                part.second && parts.subList(index + 1, parts.size).none { !it.second && it.first.intersect(part.first) != null }
            }.map { it.value.first }
        }

        /** The last rectangle covering the point decides (later operations win). */
        fun contains(x: Int, y: Int): Boolean {
            var inside = false
            for ((rect, added) in parts) {
                if (x >= rect.x && y >= rect.y && x < rect.x + rect.width && y < rect.y + rect.height) inside = added
            }
            return inside
        }
    }
}
