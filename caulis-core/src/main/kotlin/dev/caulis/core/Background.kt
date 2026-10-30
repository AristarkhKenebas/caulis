package dev.caulis.core

import dev.caulis.render.Color
import java.awt.Graphics2D
import java.nio.file.Path

/**
 * What the desktop shows behind everything: a colour, a gradient, a picture, your own
 * drawing, or a shader — still or moving. Set it with [Desktop.background] (or a different
 * one per monitor and workspace with [Desktop.backgroundFor]).
 *
 * A wallpaper program (swaybg, hyprpaper, swww, mpvpaper, ... — anything on the background
 * layer) draws on top of this and wins: both can be there, nothing conflicts.
 *
 * ```
 * Background.color(Color.hex("#1e1e2e"))
 * Background.gradient(Color.hex("#1e3c72"), Color.hex("#2a5298"))
 * Background.image(Path.of("/usr/share/backgrounds/forest.jpg"))
 * Background.shader(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(at, 0.5 + 0.5 * sin(time), 1.0); }"))
 * Background.painted { g, look -> g.fillOval(0, 0, look.width, look.height) }
 * ```
 */
sealed class Background {

    /** One colour. */
    data class Solid(val color: Color) : Background()

    /** From [from] to [to] along [degrees] (0: left to right, 90: top to bottom). */
    data class Gradient(val from: Color, val to: Color, val degrees: Double = 90.0) : Background()

    /**
     * A picture from a file (PNG, JPEG, BMP or GIF), fitted as [fit] says; [behind] shows
     * where it does not cover the monitor. Read in the background: until it is, [behind].
     * With [span], one picture over all monitors together, each showing its part (in desktop
     * units: it goes on seamlessly from one to the next, whatever their scales).
     */
    data class Image(val path: Path, val fit: Fit = Fit.FILL, val behind: Color = Color(0f, 0f, 0f), val span: Boolean = false) : Background()

    /** Drawn by your own code into a picture of the monitor's size (again when its size or scale changes). */
    class Painted(val painter: BackgroundPainter) : Background()

    /** Drawn by a shader on the GPU, also moving (see [WallpaperShader]). */
    data class Shader(val shader: WallpaperShader) : Background()

    /** How a picture covers its monitor. */
    enum class Fit {
        /** All of the monitor, cutting off what sticks out (the usual). */
        FILL,

        /** All of the picture, with [Image.behind] around it. */
        FIT,

        /** Stretched to the monitor, its proportions changed. */
        STRETCH,

        /** In the middle at its own size. */
        CENTER,

        /** Repeated from the top left corner at its own size. */
        TILE,
    }

    companion object {
        @JvmStatic fun color(color: Color): Background = Solid(color)

        @JvmStatic @JvmOverloads
        fun gradient(from: Color, to: Color, degrees: Double = 90.0): Background = Gradient(from, to, degrees)

        @JvmStatic @JvmOverloads
        fun image(path: Path, fit: Fit = Fit.FILL, behind: Color = Color(0f, 0f, 0f), span: Boolean = false): Background = Image(path, fit, behind, span)

        @JvmStatic fun painted(painter: BackgroundPainter): Background = Painted(painter)

        @JvmStatic fun shader(shader: WallpaperShader): Background = Shader(shader)

        /** Caulis's usual dark grey. */
        @JvmField val USUAL: Background = Solid(Color(0.16f, 0.18f, 0.22f))
    }
}

/** What a [BackgroundPainter] paints: [width] x [height] pixels of [monitor], [scale] pixels per unit. */
class BackgroundLook(val width: Int, val height: Int, val scale: Double, val monitor: String, val workspace: Int)

/** Paints a background into `g` (see [Background.painted]). */
fun interface BackgroundPainter {
    fun paint(g: Graphics2D, look: BackgroundLook)
}

/**
 * A background drawn by the GPU: GLSL (OpenGL ES 3) with one function that gets a point of
 * the monitor (0 to 1, from the top left) and returns its colour:
 *
 * ```
 * vec4 wallpaper(vec2 at) {
 *     // time: seconds since the start; size: the monitor in pixels; pointer: where the
 *     // pointer is (0..1, or -1 when it is on another monitor); workspace: its number;
 *     // image(uv): the picture of [image], if one is given.
 *     // Over several monitors: desktopAt(at) is the place on all of them together (0..1),
 *     // unitsAt(at) in desktop units (seamless whatever the scales), desktopPointer the
 *     // pointer on all of them; monitor and desktop are (x, y, width, height) in units,
 *     // scale its pixels per unit, physicalSize its size in millimetres (0 if unknown).
 *     float wave = 0.5 + 0.5 * sin(at.x * 10.0 + time);
 *     return vec4(0.1, 0.2 * wave, 0.4, 1.0);
 * }
 * ```
 *
 * More it can read, for wallpapers that answer the pointer and match a look:
 *
 * - `vec2 pointerPixels` — the pointer in the picture's pixels from its top left (-1, -1 when elsewhere)
 * - `vec2 pointerVelocity` — how fast the pointer moves, in `at` units (0..1 of the picture) a second, smoothed
 * - `vec4 clicks[4]`, `int clickCount` — the last presses, newest first: x, y (as `at`), the
 *   `time` it was pressed at (so `time - clicks[0].z` is its age) and the button (1 left, 2 middle, 3 right)
 * - `vec4 colors[8]`, `int colorCount` — [colors] given to it (plain, not premultiplied)
 * - each of [values] as a `float` of its name (`speed`, `intensity`, ...): settings of the shader
 * - `vec4 view` — the part of the whole picture shown (a view moving and zooming over it); `viewAt(at)`
 *
 * A shader that uses `time` moves: it is drawn [framesPerSecond] times a second (more costs
 * more power; panels and windows above it are drawn again with it), only while it is seen.
 * One that uses only the pointer (`pointer`, `pointerPixels`, `pointerVelocity`, `clicks`,
 * `desktopPointer`) is drawn anew only while the pointer moves or a click is fresh. One that
 * does not compile is reported once in the log, and [fallback] (else [Background.USUAL]) shows.
 * [Desktop.wallpaperShadersPaused] and [Desktop.wallpaperFpsLimit] hold them all still or slower.
 */
class WallpaperShader private constructor(
    private val inline: String?,
    private val file: WatchedText?,
    /** A picture the shader can read with `image(uv)`, or null. */
    val image: Path?,
    /** How often a moving shader is drawn anew. */
    val framesPerSecond: Int,
    /** Colours handed to it (`colors[0]`.. and `colorCount`), at most [MAX_COLORS]: a look's accents. */
    val colors: List<Color>,
    /** Settings of the shader by name, each a `float` of that name in its code (`speed`, `intensity`, ...). */
    val values: Map<String, Float>,
    /** What shows if it does not compile (null: [Background.USUAL]'s colour). */
    val fallback: Color?,
) {
    /** A shader written in your code. */
    @JvmOverloads
    constructor(code: String, image: Path? = null, framesPerSecond: Int = 30) : this(code, null, image, framesPerSecond, emptyList(), emptyMap(), null)

    init {
        require(framesPerSecond in 1..240) { "framesPerSecond must be 1 to 240" }
        require(colors.size <= MAX_COLORS) { "a wallpaper shader gets up to $MAX_COLORS colours, not ${colors.size}" }
        for (name in values.keys) {
            require(NAME.matches(name) && !name.startsWith("gl_") && !name.startsWith("caulis") && name !in RESERVED) {
                "\"$name\" cannot be a shader value's name (letters, digits and _, not one of Caulis's own: ${RESERVED.joinToString()})"
            }
        }
        require(values.values.all { it.isFinite() }) { "a shader value is a number, not ${values.values.firstOrNull { !it.isFinite() }}" }
    }

    /**
     * The same shader with other [colors], [values], [fallback] or [framesPerSecond] (the
     * file, if any, is still followed: a saved change shows at once).
     */
    @JvmOverloads
    fun with(
        colors: List<Color> = this.colors,
        values: Map<String, Float> = this.values,
        fallback: Color? = this.fallback,
        framesPerSecond: Int = this.framesPerSecond,
    ): WallpaperShader = WallpaperShader(inline, file, image, framesPerSecond, colors.toList(), LinkedHashMap(values), fallback)

    /** The GLSL code with the `wallpaper` function (and any helpers it needs); a file's as it is now. */
    val code: String get() = inline ?: file!!.text

    /** Why its file cannot be read now (null: it can, or it is written in code). */
    val fileProblem: String? get() = file?.problem

    /** The file it comes from (null: written in code). */
    val path: Path? get() = file?.path

    /** What to call it in messages: its file's name, or "a wallpaper shader". */
    val name: String get() = file?.path?.fileName?.toString() ?: "a wallpaper shader"

    /** True if it changes by itself (it uses `time`, or the pointer: `pointer`, `pointerPixels`, `pointerVelocity`, `clicks`, `desktopPointer`). */
    val moving: Boolean get() = uses().let { it.time || it.pointer }

    /** True if it uses `time`: drawn [framesPerSecond] times a second while seen. */
    val usesTime: Boolean get() = uses().time

    /** True if it follows the pointer or clicks: drawn anew while the pointer moves (and a click is fresh). */
    val usesPointer: Boolean get() = uses().pointer

    private class Uses(val code: String, val time: Boolean, val pointer: Boolean)

    @Volatile private var usesNow: Uses? = null

    // What the code reads, found once per version of it.
    private fun uses(): Uses {
        val now = code
        usesNow?.let { if (it.code === now) return it }
        return Uses(now, TIME.containsMatchIn(now), POINTER.containsMatchIn(now)).also { usesNow = it }
    }

    // The same code, picture and settings is the same shader (compiled once, a mistake said once).
    override fun equals(other: Any?) = other is WallpaperShader && other.code == code && other.image == image && other.framesPerSecond == framesPerSecond &&
        other.colors == colors && other.values == values && other.fallback == fallback
    override fun hashCode() = code.hashCode() * 31 + (image?.hashCode() ?: 0)
    override fun toString() = "WallpaperShader(${code.length} characters${image?.let { ", $it" } ?: ""})"

    /** The whole fragment shader around [code], as the renderer compiles it. */
    internal fun source(code: String = this.code): String = """#version 300 es
        precision highp float;
        in vec2 uv;
        uniform float time;
        uniform vec2 size;
        uniform vec2 pointer;
        uniform vec2 pointerPixels;
        uniform vec2 pointerVelocity;
        uniform vec4 clicks[4];
        uniform int clickCount;
        uniform vec4 colors[8];
        uniform int colorCount;
        uniform float workspace;
        uniform float alpha;
        uniform sampler2D picture;
        uniform bool hasPicture;
        uniform vec4 monitor;
        uniform vec4 desktop;
        uniform float scale;
        uniform vec2 physicalSize;
        uniform vec2 desktopPointer;
        uniform vec4 view;
        """ + values.keys.joinToString("") { "uniform float $it;\n" } + """
        out vec4 caulisColor;
        vec4 image(vec2 p) { return hasPicture ? texture(picture, p) : vec4(0.0); }
        vec2 unitsAt(vec2 p) { return monitor.xy + p * monitor.zw; }
        vec2 desktopAt(vec2 p) { return (unitsAt(p) - desktop.xy) / desktop.zw; }
        vec2 viewAt(vec2 p) { return view.xy + p * view.zw; }
        #line 1
        """ + code + """
        void main() { caulisColor = wallpaper(uv) * alpha; }"""

    companion object {
        /** How many colours a wallpaper shader can be handed. */
        const val MAX_COLORS = 8

        // Names a shader value cannot have (Caulis's own uniforms and helpers).
        private val RESERVED = setOf(
            "time", "size", "pointer", "pointerPixels", "pointerVelocity", "clicks", "clickCount", "colors", "colorCount", "workspace", "alpha",
            "picture", "hasPicture", "monitor", "desktop", "scale", "physicalSize", "desktopPointer", "view", "uv", "image", "unitsAt", "desktopAt",
            "viewAt", "wallpaper", "main",
        )
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TIME = Regex("""\btime\b""")
        private val POINTER = Regex("""\b(pointer|pointerPixels|pointerVelocity|clicks|clickCount|desktopPointer)\b""")

        /**
         * A shader from a file (e.g. next to your configuration): read again whenever you
         * save it, and drawn at once — no restart, no reload of anything else.
         */
        @JvmStatic
        @JvmOverloads
        fun file(path: Path, image: Path? = null, framesPerSecond: Int = 30): WallpaperShader =
            WallpaperShader(null, WatchedText.of(path), image, framesPerSecond, emptyList(), emptyMap(), null)

        /** A slow, soft play of colours — an example to start from. */
        @JvmField val AURORA = WallpaperShader(
            """
            vec4 wallpaper(vec2 at) {
                float t = time * 0.05;
                vec3 a = vec3(0.10, 0.12, 0.25);
                vec3 b = vec3(0.05, 0.35, 0.40);
                vec3 c = vec3(0.30, 0.10, 0.35);
                float w1 = 0.5 + 0.5 * sin(at.x * 3.0 + t * 5.0 + sin(at.y * 4.0 + t * 3.0));
                float w2 = 0.5 + 0.5 * sin(at.y * 2.5 - t * 4.0 + sin(at.x * 3.5 - t * 2.0));
                vec3 color = mix(mix(a, b, w1 * (1.0 - at.y)), c, w2 * at.y * 0.8);
                return vec4(color, 1.0);
            }
            """.trimIndent(),
            framesPerSecond = 30,
        )
    }
}

/**
 * The part of a monitor's background shown on it, as fractions of the whole picture: [x], [y]
 * its top left, [width], [height] its size (all 0..1) — a view moving over a big wallpaper and
 * zooming into it, as a canvas moves ([Desktop.setBackgroundView]). A picture or a painted
 * background shows that part stretched over the monitor; a shader gets it as `view` (and
 * `viewAt(at)` turns its point into the view's).
 */
data class BackgroundView(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(width > 0 && height > 0) { "a background view needs a size above 0 ($width x $height)" }
    }

    companion object {
        /** All of it (as without a view). */
        @JvmField val ALL = BackgroundView(0.0, 0.0, 1.0, 1.0)
    }
}
