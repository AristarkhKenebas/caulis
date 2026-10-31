package dev.caulis.core

import dev.caulis.geometry.Rect
import kotlin.math.roundToInt

/**
 * One moment of a window's animation, what a [WindowAnimation] is given. Rectangles are in
 * desktop units.
 */
class Motion(
    /** The window (null for a window that is gone: only its last look is left). */
    val window: Window?,
    /** Where it starts and where it ends; for appearing and going away both are its place. */
    val from: Rect,
    val to: Rect,
    /** How far along it is (0 to 1, through the animation's [Curve]); for going away, 1 → 0. */
    val progress: Double,
    /** How much of its time has passed (0 to 1, without the curve). */
    val time: Double,
    /** The free area of its monitor (inside panels). */
    val area: Rect,
) {
    /** The rectangle [progress] of the way from [from] to [to]. */
    fun between(): Rect = mix(from, to, progress)

    /** A copy with another start or end. */
    fun with(from: Rect = this.from, to: Rect = this.to) = Motion(window, from, to, progress, time, area)

    companion object {
        /** The rectangle [t] of the way from [a] to [b]. */
        @JvmStatic
        fun mix(a: Rect, b: Rect, t: Double): Rect {
            fun m(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
            return Rect(m(a.x, b.x), m(a.y, b.y), m(a.width, b.width).coerceAtLeast(1), m(a.height, b.height).coerceAtLeast(1))
        }
    }
}

/**
 * How a window is drawn at one moment: its picture stretched into [rect], at [alpha], and
 * through [shader] if it has one (a [WindowShader] gets [shaderFrom], [shaderTo] and the
 * progress to bend the picture, e.g. the genie).
 */
class WindowFrame @JvmOverloads constructor(
    val rect: Rect,
    val alpha: Float = 1f,
    val shader: WindowShader? = null,
    /** For the shader: where the picture starts and ends (default: [rect]). */
    val shaderFrom: Rect = rect,
    val shaderTo: Rect = rect,
    val progress: Double = 1.0,
)

/**
 * A window's animation: given a [Motion] (where it goes, how far along), it says how the
 * window is drawn ([WindowFrame]). Use the ready ones, put them together with `+`, give
 * them their own time and curve — or write your own in one line:
 *
 * ```
 * WindowAnimation.pop()                                   // fades in growing from 90%
 * WindowAnimation.fade() + WindowAnimation.slide(dy = 60) // fades in rising from below
 * WindowAnimation.fade() + WindowAnimation.scale(0.5)    // fades in growing from half its size
 * WindowAnimation.pop(0.6).lasting(400).curved(Curve.OVERSHOOT)
 * WindowAnimation.genie()                                 // minimizing: flows into the dock icon
 * WindowAnimation { m -> WindowFrame(m.between(), alpha = m.progress.toFloat()) } // your own
 * ```
 */
fun interface WindowAnimation {
    fun frame(motion: Motion): WindowFrame

    /** How long it takes in milliseconds (null: [Desktop.animationMillis]). */
    val millis: Long? get() = null

    /** Its curve (null: the one of [Animations]). */
    val curve: Curve? get() = null

    /** The same, taking [millis] milliseconds. */
    fun lasting(millis: Long): WindowAnimation {
        require(millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
        return Timed(this, millis, curve)
    }

    /** The same, along [curve]. */
    fun curved(curve: Curve): WindowAnimation = Timed(this, millis, curve)

    /** This, then [other] on top: rectangles changed by both, opacities multiplied, [other]'s shader if it has one. */
    operator fun plus(other: WindowAnimation): WindowAnimation {
        val first = this
        return Timed(WindowAnimation { motion ->
            val a = first.frame(motion)
            val b = other.frame(motion.with(from = motion.from, to = a.rect))
            if (b.shader != null) b else WindowFrame(b.rect, a.alpha * b.alpha, a.shader, a.shaderFrom, a.shaderTo, a.progress)
        }, other.millis ?: millis, other.curve ?: curve)
    }

    companion object {
        /** Nothing moves: it is there (or gone) at once. */
        @JvmField val NONE: WindowAnimation = Timed(WindowAnimation { WindowFrame(it.to) }, 0, null)

        /** It moves and resizes from where it was to where it goes (how windows glide by default). */
        @JvmStatic
        fun move(): WindowAnimation = WindowAnimation { WindowFrame(it.between()) }

        /** It fades in (or out). */
        @JvmStatic
        fun fade(): WindowAnimation = WindowAnimation { WindowFrame(it.to, alpha = it.progress.toFloat().coerceIn(0f, 1f)) }

        /** It grows from [from] of its size (or shrinks to it), around its middle. */
        @JvmStatic
        @JvmOverloads
        fun scale(from: Double = 0.9): WindowAnimation = WindowAnimation { m ->
            WindowFrame(scaled(m.to, from + (1 - from) * m.progress))
        }

        /** It comes in from [dx], [dy] units away (or goes there). */
        @JvmStatic
        @JvmOverloads
        fun slide(dx: Int = 0, dy: Int = 40): WindowAnimation = WindowAnimation { m ->
            val away = 1 - m.progress
            WindowFrame(m.to.translate((dx * away).roundToInt(), (dy * away).roundToInt()))
        }

        /** It fades in growing from [from] of its size (or shrinks away): `fade() + scale(from)`, as on macOS and GNOME. */
        @JvmStatic
        @JvmOverloads
        fun pop(from: Double = 0.9): WindowAnimation = fade() + scale(from)

        /** It flies from where it is into [Motion.to] (minimizing: its dock icon), shrinking. */
        @JvmStatic
        fun flyTo(): WindowAnimation = move()

        /**
         * The genie: the window flows into its dock icon, its bottom first, narrowing like
         * smoke into a lamp (and out again when restored). Bends the picture with a shader.
         */
        @JvmStatic
        fun genie(): WindowAnimation = WindowAnimation { m ->
            val box = m.from.union(m.to)
            WindowFrame(box, shader = WindowShader.GENIE, shaderFrom = m.from, shaderTo = m.to, progress = m.progress)
        }

        /**
         * A look by name, with what it may take after it (as Hyprland's styles): "popin 80%"
         * (fades in growing from 80%), "pop", "fade", "scale 50%", "slide" (from below),
         * "slide left 120" (from 120 units to the left; also right, up, down, top, bottom),
         * "slide-up", "genie", "fly", "move", "none". Null for something else.
         */
        @JvmStatic
        fun named(style: String): WindowAnimation? {
            val words = style.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            fun percent(at: Int, usual: Double): Double? {
                val word = words.getOrNull(at) ?: return usual
                val n = word.removeSuffix("%").toDoubleOrNull() ?: return null
                return (if (word.endsWith("%") || n > 1.0) n / 100 else n).takeIf { it in 0.0..1.0 }
            }
            return when (words[0]) {
                "popin", "pop" -> percent(1, if (words[0] == "popin") 0.8 else 0.9)?.let { pop(it) }
                "fade", "fadein" -> fade()
                "scale" -> percent(1, 0.9)?.let { fade() + scale(it) }
                "slide" -> {
                    var dx = 0
                    var dy = 40
                    var distance = 40
                    var side = "down"
                    for (word in words.drop(1)) {
                        val n = word.toIntOrNull()
                        if (n != null && n in 0..5000) distance = n else if (word in listOf("left", "right", "up", "down", "top", "bottom")) side = word else return null
                    }
                    when (side) {
                        "left" -> { dx = -distance; dy = 0 }
                        "right" -> { dx = distance; dy = 0 }
                        "up", "top" -> dy = -distance
                        else -> dy = distance
                    }
                    fade() + slide(dx, dy)
                }
                "slide-up" -> fade() + slide()
                "genie" -> genie()
                "fly" -> flyTo()
                "move" -> move()
                "none" -> NONE
                else -> null
            }
        }

        /** [rect] grown or shrunk by [scale] around its middle. */
        @JvmStatic
        fun scaled(rect: Rect, scale: Double): Rect {
            val width = (rect.width * scale).roundToInt().coerceAtLeast(1)
            val height = (rect.height * scale).roundToInt().coerceAtLeast(1)
            return Rect(rect.x + (rect.width - width) / 2, rect.y + (rect.height - height) / 2, width, height)
        }
    }

    private class Timed(val inner: WindowAnimation, override val millis: Long?, override val curve: Curve?) : WindowAnimation {
        override fun frame(motion: Motion) = inner.frame(motion)
    }
}

/**
 * A shader that draws a window's picture during an animation: GLSL code (OpenGL ES 3)
 * with one function, which gets the place in the drawn rectangle and returns the colour:
 *
 * ```
 * vec4 effect(vec2 at) {
 *     // at: 0..1 in the drawn rectangle; window(uv) reads the window's picture (uv 0..1,
 *     // see-through outside); progress: 0..1; from, to: the picture's start and end as
 *     // (x, y, width, height) in the drawn rectangle's 0..1; size: its size in pixels.
 *     return window(at) * progress;
 * }
 * ```
 *
 * A shader that does not compile is reported in the log; the window is then drawn without.
 */
class WindowShader private constructor(private val inline: String?, private val file: WatchedText?) {

    /** A shader written in your code. */
    constructor(code: String) : this(code, null)

    /** The GLSL code with the `effect` function (and any helpers it needs); a file's as it is now. */
    val code: String get() = inline ?: file!!.text

    /** Why its file cannot be read now (null: it can, or it is written in code). */
    val fileProblem: String? get() = file?.problem

    /** The file it comes from (null: written in code). */
    val path: java.nio.file.Path? get() = file?.path

    /** What to call it in messages: its file's name, or "a window shader". */
    val name: String get() = file?.path?.fileName?.toString() ?: "a window shader"

    // The same code is the same shader (compiled once, a mistake in it said once).
    override fun equals(other: Any?) = other is WindowShader && other.code == code
    override fun hashCode() = code.hashCode()
    override fun toString() = "WindowShader(${file?.path ?: "${code.length} characters"})"

    companion object {
        /**
         * A shader from a file (e.g. next to your configuration): read again whenever you
         * save it, and used at once — no restart, no reload of anything else.
         */
        @JvmStatic
        fun file(path: java.nio.file.Path): WindowShader = WindowShader(null, WatchedText.of(path))

        /** The genie: rows of the picture move towards [to] one after another, the far edge last. */
        @JvmField val GENIE = WindowShader(
            """
            vec4 effect(vec2 at) {
                // Turned so that it always flows down (into a dock below, above, left or right).
                vec2 dir = (to.xy + to.zw * 0.5) - (from.xy + from.zw * 0.5);
                bool vertical = abs(dir.y) >= abs(dir.x);
                vec2 p = vertical ? at : at.yx;
                vec4 f = vertical ? from : from.yxwz;
                vec4 t = vertical ? to : to.yxwz;
                bool down = (vertical ? dir.y : dir.x) >= 0.0;
                if (!down) { p.y = 1.0 - p.y; f.y = 1.0 - f.y - f.w; t.y = 1.0 - t.y - t.w; }
                // First half: its far edge stretches to the icon, narrowing; second half: it slides in.
                float squeeze = clamp(progress * 2.0, 0.0, 1.0);
                float slide = clamp(progress * 2.0 - 1.0, 0.0, 1.0);
                float top = mix(f.y, t.y, slide);
                float bottom = mix(f.y + f.w, t.y + t.w, squeeze);
                if (p.y < top || p.y > bottom || bottom - top < 0.0001) return vec4(0.0);
                float v = (p.y - top) / (bottom - top);
                // Rows nearer the icon are narrower (a soft S from the window's top to the icon).
                float near = smoothstep(0.0, 1.0, (p.y - f.y) / max(t.y + t.w - f.y, 0.0001));
                float k = mix(squeeze * near, 1.0, slide);
                float left = mix(f.x, t.x, k);
                float right = mix(f.x + f.z, t.x + t.z, k);
                if (p.x < left || p.x > right || right - left < 0.0001) return vec4(0.0);
                vec2 uv = vec2((p.x - left) / (right - left), down ? v : 1.0 - v);
                return window(vertical ? uv : uv.yx);
            }
            """.trimIndent(),
        )
    }
}
