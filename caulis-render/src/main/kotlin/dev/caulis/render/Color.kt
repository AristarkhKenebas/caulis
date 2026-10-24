package dev.caulis.render

/**
 * A colour with transparency, each part 0..1 (alpha 0 = invisible, 1 = solid).
 *
 * `Color.hex("#89b4fa")`, `Color.hex("#89b4fa80")` (with alpha) or `Color(0.5f, 0.7f, 1f)`.
 */
data class Color(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1f) {
    init {
        require(red in 0f..1f && green in 0f..1f && blue in 0f..1f && alpha in 0f..1f) { "colour parts must be between 0 and 1: $this" }
    }

    /** The same colour, [alpha] transparent. */
    fun withAlpha(alpha: Float) = copy(alpha = alpha)

    companion object {
        @JvmField val BLACK = Color(0f, 0f, 0f)
        @JvmField val WHITE = Color(1f, 1f, 1f)
        @JvmField val TRANSPARENT = Color(0f, 0f, 0f, 0f)

        /** "#rrggbb" or "#rrggbbaa" (the "#" may be left out), as in CSS and most themes. */
        @JvmStatic
        fun hex(text: String): Color {
            val digits = text.removePrefix("#")
            require((digits.length == 6 || digits.length == 8) && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "\"$text\" is not a colour like \"#89b4fa\" or \"#89b4fa80\""
            }
            fun part(i: Int) = digits.substring(i * 2, i * 2 + 2).toInt(16) / 255f
            return Color(part(0), part(1), part(2), if (digits.length == 8) part(3) else 1f)
        }
    }
}
