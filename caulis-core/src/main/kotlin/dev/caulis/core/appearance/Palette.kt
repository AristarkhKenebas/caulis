package dev.caulis.core.appearance

import dev.caulis.render.Color
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colours made from one colour — a wallpaper's, or one you choose — as phones do (Material
 * You, matugen): a [seed] and a [style] give a whole [PaletteScheme] for light and dark, its
 * roles (primary, surface, text, ...) in tones that read well together.
 *
 * ```
 * val seed = Palette.seedOf(wallpaperImage)          // the wallpaper's most telling colour
 * val palette = Palette(seed, PaletteStyle.VIBRANT)
 * val dark = palette.scheme(dark = true)
 * dark.primary; dark.surface; dark.onSurface
 * palette.code                                        // "caulis-palette:#5b8cc8:vibrant": to share
 * ```
 *
 * The tones are made in OKLCH (perceived lightness, colourfulness, hue), so a role has the
 * same lightness whatever the hue.
 */
class Palette(
    /** The colour everything is made from. */
    val seed: Color,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
) {
    private val seedLch = Oklch.of(seed)

    /** The roles' colours for the dark ([dark]) or light look. */
    fun scheme(dark: Boolean): PaletteScheme {
        val h = seedLch.h
        val c = style.chromas(seedLch.c)
        fun tone(l: Double, chroma: Double, hue: Double) = Oklch(l, chroma, hue).toColor()
        val pH = h + style.primaryTurn
        val sH = h + style.secondaryTurn
        val tH = h + style.tertiaryTurn
        val n = c.neutral
        val nv = c.neutral * 2
        return if (dark) PaletteScheme(
            dark = true,
            primary = tone(0.80, c.primary, pH), onPrimary = tone(0.28, c.primary * 0.8, pH),
            primaryContainer = tone(0.38, c.primary * 0.9, pH), onPrimaryContainer = tone(0.92, c.primary * 0.5, pH),
            secondary = tone(0.80, c.secondary, sH), onSecondary = tone(0.28, c.secondary, sH),
            secondaryContainer = tone(0.36, c.secondary, sH), onSecondaryContainer = tone(0.92, c.secondary * 0.5, sH),
            tertiary = tone(0.80, c.tertiary, tH), onTertiary = tone(0.28, c.tertiary, tH),
            surface = tone(0.20, n, h), surfaceContainer = tone(0.24, n, h), surfaceBright = tone(0.30, n, h),
            onSurface = tone(0.93, n, h), onSurfaceVariant = tone(0.78, nv, h),
            outline = tone(0.58, nv, h), outlineVariant = tone(0.38, nv, h),
            error = tone(0.74, 0.14, 25.0), onError = tone(0.28, 0.12, 25.0),
        ) else PaletteScheme(
            dark = false,
            primary = tone(0.50, c.primary, pH), onPrimary = tone(0.99, 0.0, pH),
            primaryContainer = tone(0.90, c.primary * 0.45, pH), onPrimaryContainer = tone(0.28, c.primary * 0.8, pH),
            secondary = tone(0.50, c.secondary, sH), onSecondary = tone(0.99, 0.0, sH),
            secondaryContainer = tone(0.90, c.secondary * 0.6, sH), onSecondaryContainer = tone(0.28, c.secondary, sH),
            tertiary = tone(0.52, c.tertiary, tH), onTertiary = tone(0.99, 0.0, tH),
            surface = tone(0.975, n * 0.6, h), surfaceContainer = tone(0.94, n, h), surfaceBright = tone(0.99, n * 0.4, h),
            onSurface = tone(0.24, n, h), onSurfaceVariant = tone(0.44, nv, h),
            outline = tone(0.62, nv, h), outlineVariant = tone(0.84, nv, h),
            error = tone(0.52, 0.17, 25.0), onError = tone(0.99, 0.0, 25.0),
        )
    }

    /** A short text that gives this palette again ([parse]): to share it, or keep it in a configuration. */
    val code: String get() = "caulis-palette:${hexOf(seed).take(7)}:${style.name.lowercase()}"

    override fun toString() = code

    override fun equals(other: Any?) = other is Palette && other.code == code

    override fun hashCode() = code.hashCode()

    companion object {
        /** A palette from its [code] ("caulis-palette:#5b8cc8:vibrant", or "#5b8cc8:vibrant", or "#5b8cc8"). */
        @JvmStatic
        fun parse(code: String): Palette {
            val parts = code.trim().removePrefix("caulis-palette:").split(':')
            val seed = Color.hex(parts[0])
            val style = parts.getOrNull(1)?.let { PaletteStyle.named(it) } ?: PaletteStyle.TONAL_SPOT
            return Palette(seed, style)
        }

        /**
         * The most telling colour of [image] (a wallpaper): the hue most of its colourful
         * pixels share, weighted by how colourful they are; a calm blue-grey if it has hardly
         * any colour.
         */
        @JvmStatic
        fun seedOf(image: BufferedImage): Color {
            val step = maxOf(1, maxOf(image.width, image.height) / 96)
            val bins = DoubleArray(36)
            val chromas = Array(36) { ArrayList<Double>() }
            val lightness = Array(36) { ArrayList<Double>() }
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val rgb = image.getRGB(x, y)
                    val lch = Oklch.of(Color(((rgb shr 16) and 0xff) / 255f, ((rgb shr 8) and 0xff) / 255f, (rgb and 0xff) / 255f))
                    // Colourful, and neither nearly black nor nearly white.
                    if (lch.c > 0.035 && lch.l in 0.25..0.92) {
                        val bin = ((lch.h.mod(360.0)) / 10).toInt().coerceIn(0, 35)
                        bins[bin] += lch.c
                        chromas[bin] += lch.c
                        lightness[bin] += lch.l
                    }
                    x += step
                }
                y += step
            }
            // The best hue: a bin with its neighbours (a colour spread over two bins counts once).
            val best = (0 until 36).maxByOrNull { bins[it] + 0.5 * (bins[(it + 35) % 36] + bins[(it + 1) % 36]) }
            if (best == null || bins[best] <= 0.0) return Oklch(0.6, 0.03, 250.0).toColor()
            val c = chromas[best].sorted()[chromas[best].size / 2].coerceIn(0.05, 0.2)
            val l = lightness[best].average().coerceIn(0.45, 0.75)
            return Oklch(l, c, best * 10.0 + 5.0).toColor()
        }

        /** "#rrggbb" (and its alpha when not opaque). */
        @JvmStatic
        fun hexOf(color: Color): String {
            fun two(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255).toString(16).padStart(2, '0')
            return "#" + two(color.red) + two(color.green) + two(color.blue) + if (color.alpha < 1f) two(color.alpha) else ""
        }
    }
}

/** How colourful a [Palette] is, as a Pixel's wallpaper styles. */
enum class PaletteStyle(
    internal val primaryTurn: Double = 0.0,
    internal val secondaryTurn: Double = 0.0,
    internal val tertiaryTurn: Double = 60.0,
) {
    /** Calm: the seed's hue, softened (Pixel's default). */
    TONAL_SPOT,

    /** Strong colours: the seed's hue at its fullest. */
    VIBRANT(secondaryTurn = 15.0, tertiaryTurn = 50.0),

    /** Playful: primary, secondary and tertiary of hues apart. */
    EXPRESSIVE(primaryTurn = -40.0, secondaryTurn = 90.0, tertiaryTurn = 180.0),

    /** Barely coloured: greys with a hint of the seed. */
    NEUTRAL,

    /** Greys only. */
    MONOCHROME;

    internal data class Chromas(val primary: Double, val secondary: Double, val tertiary: Double, val neutral: Double)

    internal fun chromas(seed: Double): Chromas = when (this) {
        TONAL_SPOT -> Chromas(0.11, 0.045, 0.075, 0.012)
        VIBRANT -> Chromas(maxOf(seed, 0.17), 0.10, 0.12, 0.02)
        EXPRESSIVE -> Chromas(0.14, 0.08, 0.12, 0.025)
        NEUTRAL -> Chromas(0.04, 0.02, 0.03, 0.008)
        MONOCHROME -> Chromas(0.0, 0.0, 0.0, 0.0)
    }

    companion object {
        /** By name: "tonal" (or "tonal_spot"), "vibrant", "expressive", "neutral", "monochrome". */
        @JvmStatic
        fun named(name: String): PaletteStyle = when (name.trim().lowercase().replace('-', '_')) {
            "tonal", "tonal_spot", "default" -> TONAL_SPOT
            "vibrant" -> VIBRANT
            "expressive" -> EXPRESSIVE
            "neutral" -> NEUTRAL
            "monochrome", "mono" -> MONOCHROME
            else -> throw IllegalArgumentException("\"$name\" is not a palette style (tonal, vibrant, expressive, neutral, monochrome)")
        }
    }
}

/** The roles of a [Palette] in the light or dark look: what to paint each part with. */
data class PaletteScheme(
    val dark: Boolean,
    /** Buttons, the accent, what is chosen. */
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    /** Backgrounds: panels, menus, bars. */
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceBright: Color,
    /** Text on them, and fainter text. */
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
) {
    /** Every role by its name ("primary", "on_surface", ...). */
    fun roles(): Map<String, Color> = linkedMapOf(
        "primary" to primary, "on_primary" to onPrimary, "primary_container" to primaryContainer, "on_primary_container" to onPrimaryContainer,
        "secondary" to secondary, "on_secondary" to onSecondary, "secondary_container" to secondaryContainer, "on_secondary_container" to onSecondaryContainer,
        "tertiary" to tertiary, "on_tertiary" to onTertiary,
        "surface" to surface, "surface_container" to surfaceContainer, "surface_bright" to surfaceBright,
        "on_surface" to onSurface, "on_surface_variant" to onSurfaceVariant, "outline" to outline, "outline_variant" to outlineVariant,
        "error" to error, "on_error" to onError,
    )

    /** As JSON: {"primary": "#rrggbb", ...}. */
    fun toJson(): String = roles().entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  \"$k\": \"${Palette.hexOf(v)}\"" }

    /** As CSS for GTK (`@define-color primary #...;`, also read by waybar's style.css with @import). */
    fun toCss(): String = roles().entries.joinToString("\n", postfix = "\n") { (k, v) -> "@define-color $k ${Palette.hexOf(v)};" }
}

/** A colour in OKLCH: perceived lightness (0..1), colourfulness (0..~0.4) and hue (degrees). */
internal data class Oklch(val l: Double, val c: Double, val h: Double) {

    /** Back to sRGB; a colour no screen shows is made less colourful until one does. */
    fun toColor(): Color {
        var chroma = c
        repeat(24) {
            val rgb = rgbOf(chroma)
            if (rgb.all { it in -0.0005..1.0005 } || chroma <= 0.0) return Color(rgb[0].toFloat().coerceIn(0f, 1f), rgb[1].toFloat().coerceIn(0f, 1f), rgb[2].toFloat().coerceIn(0f, 1f))
            chroma *= 0.88
        }
        val rgb = rgbOf(0.0)
        return Color(rgb[0].toFloat().coerceIn(0f, 1f), rgb[1].toFloat().coerceIn(0f, 1f), rgb[2].toFloat().coerceIn(0f, 1f))
    }

    private fun rgbOf(chroma: Double): DoubleArray {
        val rad = Math.toRadians(h)
        val a = chroma * cos(rad)
        val b = chroma * sin(rad)
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val ll = l_ * l_ * l_
        val mm = m_ * m_ * m_
        val ss = s_ * s_ * s_
        val r = 4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss
        val g = -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss
        val bl = -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss
        return doubleArrayOf(gamma(r), gamma(g), gamma(bl))
    }

    companion object {
        fun of(color: Color): Oklch {
            val r = linear(color.red.toDouble())
            val g = linear(color.green.toDouble())
            val b = linear(color.blue.toDouble())
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
            val aa = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
            val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            val hue = Math.toDegrees(atan2(bb, aa)).let { if (it < 0) it + 360 else it }
            return Oklch(ll, sqrt(aa * aa + bb * bb), hue)
        }

        private fun linear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

        private fun gamma(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    }
}
