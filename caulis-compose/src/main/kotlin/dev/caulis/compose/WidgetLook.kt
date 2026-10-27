package dev.caulis.compose

import dev.caulis.core.Edge

/**
 * How a [ComposeWidget]'s picture is shown: [alpha] as strong, [scale] times its size about its
 * area's middle, moved by [dx], [dy] (desktop units), [reveal] of it shown from the edge
 * [revealFrom]. The compositor does it with the picture as it is, so appearing and going away
 * cost no drawing. The pointer follows the picture: a click lands where it is seen (only on
 * the part shown, while its area is where the pointer is looked for).
 */
data class WidgetLook @JvmOverloads constructor(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** How much of it shows (0..1) from [revealFrom] on: a menu unfolding. */
    val reveal: Float = 1f,
    /** The edge it unfolds from ([Edge.TOP]: from the top down; [Edge.BOTTOM] for a menu opening upwards). */
    val revealFrom: Edge = Edge.TOP,
) {
    companion object {
        /** As it is. */
        @JvmField
        val SHOWN = WidgetLook()
    }
}

/** The size of a [ComposeWidget]'s scene on a monitor, in desktop units. */
data class SceneSize(val width: Int, val height: Int)
