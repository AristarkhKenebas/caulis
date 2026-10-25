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
 * A shader that uses `time` or `pointer` moves: it is drawn [framesPerSecond] times a
 * second (more costs more power; panels and windows above it are drawn again with it).
 * One that does not compile is reported once in the log, and [Background.USUAL] shows.
 */
class WallpaperShader private constructor(
    private val inline: String?,
    private val file: WatchedText?,
    /** A picture the shader can read with `image(uv)`, or null. */
    val image: Path?,
    /** How often a moving shader is drawn anew. */
    val framesPerSecond: Int,
) {
    /** A shader written in your code. */
    @JvmOverloads
    constructor(code: String, image: Path? = null, framesPerSecond: Int = 30) : this(code, null, image, framesPerSecond)

    init {
        require(framesPerSecond in 1..240) { "framesPerSecond must be 1 to 240" }
    }

    /** The GLSL code with the `wallpaper` function (and any helpers it needs); a file's as it is now. */
    val code: String get() = inline ?: file!!.text

    /** Why its file cannot be read now (null: it can, or it is written in code). */
    val fileProblem: String? get() = file?.problem

    /** The file it comes from (null: written in code). */
    val path: Path? get() = file?.path

    /** What to call it in messages: its file's name, or "a wallpaper shader". */
    val name: String get() = file?.path?.fileName?.toString() ?: "a wallpaper shader"

    /** True if it changes by itself (it uses `time`, `pointer` or `desktopPointer`). */
    val moving: Boolean
        get() {
            val now = code
            if (now !== movingOf) {
                movingOf = now
                movingNow = Regex("""\b(time|pointer|desktopPointer)\b""").containsMatchIn(now)
            }
            return movingNow
        }

    @Volatile private var movingOf: String? = null
    @Volatile private var movingNow = false

    // The same code and picture is the same shader (compiled once, a mistake said once).
    override fun equals(other: Any?) = other is WallpaperShader && other.code == code && other.image == image && other.framesPerSecond == framesPerSecond
    override fun hashCode() = code.hashCode() * 31 + (image?.hashCode() ?: 0)
    override fun toString() = "WallpaperShader(${code.length} characters${image?.let { ", $it" } ?: ""})"

    /** The whole fragment shader around [code], as the renderer compiles it. */
    internal fun source(code: String = this.code): String = """#version 300 es
        precision highp float;
        in vec2 uv;
        uniform float time;
        uniform vec2 size;
        uniform vec2 pointer;
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
        out vec4 caulisColor;
        vec4 image(vec2 p) { return hasPicture ? texture(picture, p) : vec4(0.0); }
        vec2 unitsAt(vec2 p) { return monitor.xy + p * monitor.zw; }
        vec2 desktopAt(vec2 p) { return (unitsAt(p) - desktop.xy) / desktop.zw; }
        vec2 viewAt(vec2 p) { return view.xy + p * view.zw; }
        #line 1
        """ + code + """
        void main() { caulisColor = wallpaper(uv) * alpha; }"""

    companion object {
        /**
         * A shader from a file (e.g. next to your configuration): read again whenever you
         * save it, and drawn at once — no restart, no reload of anything else.
         */
        @JvmStatic
        @JvmOverloads
        fun file(path: Path, image: Path? = null, framesPerSecond: Int = 30): WallpaperShader =
            WallpaperShader(null, WatchedText.of(path), image, framesPerSecond)

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
