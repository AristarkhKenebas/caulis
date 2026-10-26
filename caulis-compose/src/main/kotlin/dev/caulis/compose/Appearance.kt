package dev.caulis.compose

/**
 * How a [ComposeWidget]'s picture is shown: [alpha] as strong, [scale] times its size about its
 * area's middle, moved by [dx], [dy] (desktop units), [reveal] of it shown from the top. The compositor does it with the picture
 * as it is, so appearing and going away cost no drawing.
 */
data class Appearance(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** How much of it shows from its top down (0..1): a menu unfolding. */
    val reveal: Float = 1f,
) {
    companion object {
        /** As it is. */
        @JvmField
        val SHOWN = Appearance()
    }
}
