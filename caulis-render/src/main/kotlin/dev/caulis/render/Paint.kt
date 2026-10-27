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
 * Paint.parse("rgba(33ccffee) rgba(00ff99ee) 45deg")                               // Hyprland's own words
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
            require(degrees.isFinite() && turnsPerSecond.isFinite()) { "a gradient's angle and turns are numbers, not $degrees and $turnsPerSecond" }
        }

        override val moving get() = turnsPerSecond != 0.0
        override fun faded(alpha: Float) = copy(colors = colors.map { it.copy(alpha = it.alpha * alpha) })

        /**
         * Its colour at ([x], [y]), each 0..1 in the box (0, 0: the top left corner), [seconds]
         * into its turning — what the GPU draws there (for tests, and for drawing it without one).
         */
        @JvmOverloads
        fun colorAt(x: Double, y: Double, seconds: Double = 0.0): Color {
            val angle = Math.toRadians((degrees + turnsPerSecond * 360.0 * seconds) % 360.0)
            val dx = x - 0.5
            val dy = y - 0.5
            val n = colors.size
            if (conic) {
                // Around the middle, the last colour going back into the first.
                val turn = (Math.atan2(dy, dx) - angle) / (2 * Math.PI)
                val t = (turn - Math.floor(turn)) * n
                val i = Math.floor(t).toInt() % n
                return mix(colors[i], colors[(i + 1) % n], t - Math.floor(t))
            }
            // Across: the box's corners along the direction are the first and the last colour.
            val cx = Math.cos(angle)
            val cy = Math.sin(angle)
            val t = ((dx * cx + dy * cy) / (Math.abs(cx) * 0.5 + Math.abs(cy) * 0.5) * 0.5 + 0.5).coerceIn(0.0, 1.0)
            val at = t * (n - 1)
            val i = Math.floor(at).toInt().coerceAtMost(n - 2)
            return mix(colors[i], colors[i + 1], at - i)
        }

        private fun mix(a: Color, b: Color, t: Double): Color {
            val f = t.toFloat().coerceIn(0f, 1f)
            fun part(x: Float, y: Float) = (x + (y - x) * f).coerceIn(0f, 1f)
            return Color(part(a.red, b.red), part(a.green, b.green), part(a.blue, b.blue), part(a.alpha, b.alpha))
        }
    }

    /**
     * A shader of your own: GLSL (OpenGL ES 3) with `vec4 paint(vec2 at)` — `at` is 0..1 in
     * the box being filled (the window and its border, or its shadow); return a plain (not
     * premultiplied) colour. [code] is asked each time it is drawn (a file can change);
     * [name] is what messages call it. What it can read besides `at`:
     *
     * - `float time` — seconds, going on while it is seen (0 when its border says animate = false)
     * - `vec2 size` — the box in pixels; `vec2 pixel` — this pixel in it, from its top left corner
     * - `float borderWidth`, `float cornerRadius` — in pixels (0 for a shadow's width)
     * - `float progress` — how far along the border this pixel is, 0..1 clockwise from the top left corner
     * - `float focused`, `float urgent`, `float grouped` — 1.0 or 0.0, the window's state ([PaintState])
     * - `vec4 colors[8]`, `int colorCount` — [colors] given to it (plain, not premultiplied)
     *
     * If it does not compile, the compositor says so (with the line) and draws its first
     * colour instead — never a black or frozen screen.
     */
    class Shader(
        private val source: () -> String,
        val name: String,
        val alpha: Float,
        /** Colours handed to it (`colors[0]`.. and `colorCount`), at most [MAX_COLORS]. */
        val colors: List<Color>,
    ) : Paint() {
        constructor(code: String) : this({ code })

        @JvmOverloads
        constructor(source: () -> String, name: String = "a paint shader", alpha: Float = 1f) : this(source, name, alpha, emptyList())

        init {
            require(colors.size <= MAX_COLORS) { "a paint shader gets up to $MAX_COLORS colours, not ${colors.size}" }
        }

        /** The same shader, handed [colors]. */
        fun withColors(vararg colors: Color): Shader = Shader(source, name, alpha, colors.toList())

        /** Its code now. */
        val code: String get() = source()

        // (Asked for every frame and border: the answer is kept while the code stays the same.)
        @Volatile private var movingOf: Pair<String, Boolean>? = null

        override val moving: Boolean
            get() {
                val now = code
                movingOf?.let { (seen, moving) -> if (seen == now) return moving }
                return TIME.containsMatchIn(now).also { movingOf = now to it }
            }
        override fun faded(alpha: Float) = Shader(source, name, this.alpha * alpha, colors)
        override fun equals(other: Any?) = other is Shader && other.code == code && other.alpha == alpha && other.colors == colors
        override fun hashCode() = code.hashCode()
        override fun toString() = "Paint.Shader($name)"

        private companion object {
            val TIME = Regex("""\btime\b""")
        }
    }

    /** One colour standing for it, where only plain colours can be drawn (a shader's: its first colour, else white). */
    fun firstColor(): Color = when (this) {
        is Solid -> color
        is Gradient -> colors.first()
        is Shader -> colors.firstOrNull()?.let { it.copy(alpha = it.alpha * alpha) } ?: Color(1f, 1f, 1f, alpha)
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

        /**
         * A paint written as Hyprland writes border colours: one colour or several, then an
         * angle — `"rgba(33ccffee) rgba(00ff99ee) 45deg"`. Colours: `rgba(rrggbbaa)`,
         * `rgb(rrggbb)`, `rgba(r, g, b, a)` (0..255 and alpha 0..1), `rgb(r, g, b)`,
         * `0xaarrggbb`, `#rrggbb[aa]`. One colour is [Solid]; more are a [Gradient] (0 degrees,
         * left to right, unless an angle is given — as in Hyprland). Throws
         * IllegalArgumentException with a plain message.
         */
        @JvmStatic
        fun parse(text: String): Paint {
            val words = PARSE_WORD.findAll(text.trim()).map { it.value }.toList()
            require(words.isNotEmpty()) { "\"$text\" names no colour" }
            var degrees: Double? = null
            val colors = ArrayList<Color>()
            for (word in words) {
                if (word.endsWith("deg", ignoreCase = true)) {
                    require(degrees == null) { "\"$text\" has two angles" }
                    degrees = word.dropLast(3).toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("\"$word\" is not an angle like 45deg")
                } else {
                    colors += parseColor(word)
                }
            }
            require(colors.isNotEmpty()) { "\"$text\" names no colour" }
            require(colors.size <= MAX_COLORS) { "a gradient has 2 to $MAX_COLORS colours, not ${colors.size}" }
            return if (colors.size == 1) Solid(colors[0]) else Gradient(colors, degrees ?: 0.0)
        }

        // One colour in any of parse's ways.
        private fun parseColor(word: String): Color {
            val w = word.trim()
            fun digits(hex: String, withAlpha: Boolean): Color {
                require(hex.length == (if (withAlpha) 8 else 6) && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "\"$w\" is not a colour" }
                return Color.hex(hex)
            }
            val call = Regex("""^(rgba?)\((.*)\)$""", RegexOption.IGNORE_CASE).find(w)
            if (call != null) {
                val alpha = call.groupValues[1].length == 4
                val inside = call.groupValues[2].trim()
                if (',' !in inside) return digits(inside, alpha)
                val parts = inside.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("\"$w\" is not a colour") }
                require(parts.size == (if (alpha) 4 else 3)) { "\"$w\" needs ${if (alpha) 4 else 3} numbers" }
                require(parts.take(3).all { it in 0.0..255.0 } && (parts.size == 3 || parts[3] in 0.0..1.0)) { "\"$w\": red, green and blue are 0 to 255, alpha 0 to 1" }
                return Color((parts[0] / 255).toFloat(), (parts[1] / 255).toFloat(), (parts[2] / 255).toFloat(), parts.getOrNull(3)?.toFloat() ?: 1f)
            }
            if (w.startsWith("0x", ignoreCase = true)) {
                val hex = w.drop(2)
                require(hex.length == 8) { "\"$w\" is not a colour like 0xffaabbcc (alpha first)" }
                return digits(hex.substring(2) + hex.substring(0, 2), true)
            }
            if (w.startsWith("#")) return digits(w.drop(1), withAlpha = w.length == 9)
            throw IllegalArgumentException("\"$w\" is not a colour (rgba(rrggbbaa), rgb(rrggbb), 0xaarrggbb or #rrggbb)")
        }

        // A colour call with whatever is inside its brackets, or a word.
        private val PARSE_WORD = Regex("""[A-Za-z]+\([^)]*\)|[^\s]+""")

        /** A rainbow going round, once every [seconds]. */
        @JvmStatic @JvmOverloads
        fun rainbow(seconds: Double = 6.0): Paint = Gradient(
            listOf("#ff0000", "#ff8800", "#ffee00", "#00dd44", "#0099ff", "#6633ff", "#ff00aa").map(Color::hex),
            conic = true, turnsPerSecond = 1 / seconds,
        )
    }
}

/**
 * The state of the window a paint is drawn for, handed to a [Paint.Shader] as `focused`,
 * `urgent` and `grouped` (1.0 or 0.0) — one shader can then glow for the focused window and
 * blink for an urgent one.
 */
data class PaintState @JvmOverloads constructor(
    val focused: Boolean = false,
    val urgent: Boolean = false,
    val grouped: Boolean = false,
) {
    companion object {
        /** Nothing special (a shadow, a decoration of your own). */
        @JvmField val NONE = PaintState()
    }
}
