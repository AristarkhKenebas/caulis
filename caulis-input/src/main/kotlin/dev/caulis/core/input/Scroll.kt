package dev.caulis.core.input

/**
 * One scroll: what the wheel or the fingers did at one moment, both directions together (a
 * diagonal swipe on a touchpad is one scroll, and programs get it as one).
 *
 * [horizontal] and [vertical] are the distance in pixels (positive: right and down).
 * [horizontal120] and [vertical120] are the wheel's notches in 1/120 (a full notch is 120,
 * high-resolution wheels send parts of one); 0 for fingers. [stopsHorizontal] and
 * [stopsVertical] say the fingers stopped on that direction (programs end their kinetic
 * scrolling then). [inverted] is true with natural scrolling: the content follows the
 * fingers, so the scroll goes the other way than the fingers moved.
 */
class Scroll(
    val source: Source,
    val horizontal: Double = 0.0,
    val vertical: Double = 0.0,
    val horizontal120: Int = 0,
    val vertical120: Int = 0,
    val stopsHorizontal: Boolean = false,
    val stopsVertical: Boolean = false,
    val inverted: Boolean = false,
) {
    /** What scrolled: programs scroll a wheel in steps and fingers smoothly (wl_pointer.axis_source). */
    enum class Source {
        /** A mouse wheel's notches. */
        WHEEL,

        /** Fingers on a touchpad (or a touch screen used as a pointer). */
        FINGER,

        /** Continuous movement without a clear end: a trackpoint's scroll button, say. */
        CONTINUOUS,

        /** A wheel tilted to the side. */
        WHEEL_TILT,
    }

    /** True if nothing moves and nothing stops: there is nothing to send. */
    val isEmpty: Boolean
        get() = horizontal == 0.0 && vertical == 0.0 && horizontal120 == 0 && vertical120 == 0 && !stopsHorizontal && !stopsVertical

    /** The same scroll with only the vertical part (null if it has none). */
    fun verticalOnly(): Scroll? =
        Scroll(source, vertical = vertical, vertical120 = vertical120, stopsVertical = stopsVertical, inverted = inverted).takeIf { !it.isEmpty }

    /** The same scroll with only the horizontal part (null if it has none). */
    fun horizontalOnly(): Scroll? =
        Scroll(source, horizontal = horizontal, horizontal120 = horizontal120, stopsHorizontal = stopsHorizontal, inverted = inverted).takeIf { !it.isEmpty }

    override fun toString() =
        "Scroll(${source.name.lowercase()} x=$horizontal ($horizontal120/120) y=$vertical ($vertical120/120)" +
            "${if (stopsHorizontal) " stop-x" else ""}${if (stopsVertical) " stop-y" else ""}${if (inverted) " inverted" else ""})"
}
