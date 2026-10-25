package dev.caulis.render

/**
 * What a border or a shadow is filled with: one colour, a gradient (also turning), or a
 * shader of your own.
 *
 * ```
 * Paint.solid(Color.hex("#89b4fa"))
 * Paint.gradient(Color.hex("#33ccff"), Color.hex("#00ff99"), degrees = 45.0)        // as Hyprland's borders
 * Paint.conic(Color.hex("#ff0080"), Color.hex("#7928ca"))                          // colours around the window
 * Paint.conic(Color.hex("#00ffff"), Color.hex("#0000ff"), turnsPerSecond = 0.25)    // ... and turning
 * Paint.shader("vec4 paint(vec2 at) { return vec4(at, 0.5 + 0.5 * sin(time), 1.0); }")
 * ```
 */
sealed class Paint {

    /** True if it changes by itself (drawn again every frame while it is seen). */
    abstract val moving: Boolean

    /** The same, [alpha] as strong. */
    abstract fun faded(alpha: Float): Paint

    data class Solid(val color: Color) : Paint() {
        override val moving get() = false
        override fun faded(alpha: Float) = Solid(color.copy(alpha = color.alpha * alpha))
    }

    /**
     * Up to [MAX_COLORS] colours, evenly apart: across the box at [degrees] (0: left to right,
     * 90: top to bottom), or ([conic]) around its middle starting at [degrees] — turning
     * [turnsPerSecond] times a second. Around the middle, the last colour goes back into the first.
     */
    data class Gradient(
        val colors: List<Color>,
        val degrees: Double = 45.0,
        val conic: Boolean = false,
        val turnsPerSecond: Double = 0.0,
    ) : Paint() {
        init {
            require(colors.size in 2..MAX_COLORS) { "a gradient has 2 to $MAX_COLORS colours, not ${colors.size}" }
        }

        override val moving get() = turnsPerSecond != 0.0
        override fun faded(alpha: Float) = copy(colors = colors.map { it.copy(alpha = it.alpha * alpha) })
    }

    /**
     * A shader of your own: GLSL (OpenGL ES 3) with `vec4 paint(vec2 at)` — [at] is 0..1 in
     * the box being filled (the window and its border, or its shadow), `time` (seconds) and
     * `size` (pixels) are there; return a plain (not premultiplied) colour. [code] is asked
     * each time it is drawn (a file can change); [name] is what messages call it.
     */
    class Shader(private val source: () -> String, val name: String = "a paint shader", val alpha: Float = 1f) : Paint() {
        constructor(code: String) : this({ code })

        /** Its code now. */
        val code: String get() = source()

        override val moving: Boolean get() = Regex("""\btime\b""").containsMatchIn(code)
        override fun faded(alpha: Float) = Shader(source, name, this.alpha * alpha)
        override fun equals(other: Any?) = other is Shader && other.code == code && other.alpha == alpha
        override fun hashCode() = code.hashCode()
        override fun toString() = "Paint.Shader($name)"
    }

    /** One colour standing for it, where only plain colours can be drawn. */
    fun firstColor(): Color = when (this) {
        is Solid -> color
        is Gradient -> colors.first()
        is Shader -> Color(1f, 1f, 1f, alpha)
    }

    companion object {
        /** How many colours a gradient can have. */
        const val MAX_COLORS = 8

        @JvmStatic fun solid(color: Color): Paint = Solid(color)

        /** A gradient across the box at [degrees] (0: left to right, 90: top to bottom). */
        @JvmStatic @JvmOverloads
        fun gradient(vararg colors: Color, degrees: Double = 45.0, turnsPerSecond: Double = 0.0): Paint =
            Gradient(colors.toList(), degrees, conic = false, turnsPerSecond = turnsPerSecond)

        /** Colours around the box's middle, the last one going back into the first. */
        @JvmStatic @JvmOverloads
        fun conic(vararg colors: Color, degrees: Double = 0.0, turnsPerSecond: Double = 0.0): Paint =
            Gradient(colors.toList(), degrees, conic = true, turnsPerSecond = turnsPerSecond)

        /** A shader of your own (see [Shader]). */
        @JvmStatic fun shader(code: String): Paint = Shader(code)

        /** A rainbow going round, once every [seconds]. */
        @JvmStatic @JvmOverloads
        fun rainbow(seconds: Double = 6.0): Paint = Gradient(
            listOf("#ff0000", "#ff8800", "#ffee00", "#00dd44", "#0099ff", "#6633ff", "#ff00aa").map(Color::hex),
            conic = true, turnsPerSecond = 1 / seconds,
        )
    }
}
