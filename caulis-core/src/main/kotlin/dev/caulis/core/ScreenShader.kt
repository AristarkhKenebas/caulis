package dev.caulis.core

import java.nio.file.Path

/**
 * A shader the whole screen goes through last (as Hyprland's screen_shader): GLSL ES 3 code
 * with a function `vec4 effect(vec4 color, vec2 at)` — `color` is the screen's colour at
 * `at` (0..1 from the top left), the result is shown. `screenAt(vec2)` reads the screen
 * elsewhere, `time` (seconds) and `size` (pixels) are there to use. A [moving] one is drawn
 * every frame (only then does `time` move).
 *
 * ```
 * screenShader = ScreenShader.GRAYSCALE
 * screenShader = ScreenShader.warm(0.4f)                           // less blue light at night
 * screenShader = ScreenShader("vec4 effect(vec4 c, vec2 at) { return vec4(1.0 - c.rgb, 1.0); }")
 * screenShader = ScreenShader.file(Path.of("crt.glsl"), moving = true) // edited while running
 * ```
 *
 * While one is on, programs' pictures are never shown directly (direct scanout): every frame
 * is drawn, which costs a little more.
 */
class ScreenShader private constructor(
    private val fixedCode: String?,
    private val watched: WatchedText?,
    /** Drawn every frame (its picture changes with `time`). */
    val moving: Boolean,
    /** What it is called in the log. */
    val name: String,
) {
    @JvmOverloads
    constructor(code: String, moving: Boolean = false, name: String = "screen shader") : this(code, null, moving, name)

    /** Its code now (a file's latest version). */
    val code: String get() = fixedCode ?: watched?.text.orEmpty()

    /** Why its file cannot be read, or null. */
    val fileProblem: String? get() = watched?.problem

    override fun toString() = name

    companion object {
        /** From a file, read again whenever it is saved. */
        @JvmStatic
        @JvmOverloads
        fun file(path: Path, moving: Boolean = false): ScreenShader =
            ScreenShader(null, WatchedText.of(path), moving, path.fileName.toString())

        /** Shades of grey. */
        @JvmField
        val GRAYSCALE = ScreenShader("vec4 effect(vec4 c, vec2 at) { float g = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)); return vec4(g, g, g, 1.0); }", name = "grayscale")

        /** Colours turned around (white on black). */
        @JvmField
        val INVERT = ScreenShader("vec4 effect(vec4 c, vec2 at) { return vec4(1.0 - c.rgb, 1.0); }", name = "invert")

        /** Warmer: [amount] (0 to 1) less blue and some less green, as a night light does. */
        @JvmStatic
        fun warm(amount: Float): ScreenShader {
            require(amount in 0f..1f) { "amount is 0 to 1, not $amount" }
            val g = 1f - amount * 0.3f
            val b = 1f - amount * 0.7f
            return ScreenShader("vec4 effect(vec4 c, vec2 at) { return vec4(c.r, c.g * $g, c.b * $b, 1.0); }", name = "warm $amount")
        }

        /** The whole fragment shader for [code] (what [Rotator] draws with), the screen turned by `turn` and `flip`. */
        internal fun source(code: String?): String = """#version 300 es
            precision highp float;
            in vec2 uv;
            uniform sampler2D picture;
            uniform int turn;
            uniform int flip;
            uniform float time;
            uniform vec2 size;
            out vec4 color;
            // The picture's point shown at [p] of the screen (the turn undone).
            vec2 turned(vec2 p) {
                if (turn == 1) p = vec2(p.y, 1.0 - p.x);
                else if (turn == 2) p = vec2(1.0 - p.x, 1.0 - p.y);
                else if (turn == 3) p = vec2(1.0 - p.y, p.x);
                if (flip == 1) p.x = 1.0 - p.x;
                return p;
            }
            vec4 screenAt(vec2 at) { return vec4(texture(picture, turned(at)).rgb, 1.0); }
            ${code ?: "vec4 effect(vec4 c, vec2 at) { return c; }"}
            void main() {
                color = vec4(effect(screenAt(uv), uv).rgb, 1.0);
            }"""
    }
}
