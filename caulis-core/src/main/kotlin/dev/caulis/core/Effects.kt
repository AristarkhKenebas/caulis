package dev.caulis.core

import dev.caulis.render.Color
import dev.caulis.render.Paint
import java.nio.file.Path

/**
 * How windows (or panels) look beyond their own picture: rounded corners, a border, a
 * shadow, frosted-glass blur behind see-through parts, and transparency. Everything is
 * off in [NONE]; switch on what you like:
 *
 * ```
 * desktop.effects = Effects(
 *     cornerRadius = 10,
 *     border = Border(width = 2, focused = Color.hex("#89b4fa"), unfocused = Color.hex("#45475a")),
 *     shadow = Shadow(),
 *     blur = Blur(),
 *     inactiveOpacity = 0.92f,
 * )
 * ```
 *
 * Borders and shadows can be gradients, turning gradients or shaders of your own ([Paint]):
 *
 * ```
 * border = Border(width = 3, focused = Paint.conic(Color.hex("#33ccff"), Color.hex("#00ff99"), turnsPerSecond = 0.2)),
 * shadow = Shadow(softness = 30, color = Paint.solid(Color.hex("#89b4fa80")), offsetY = 0),   // a glow
 * inactiveShadow = Shadow(softness = 12, offsetY = 3),   // smaller when not focused (Shadow.NONE: none)
 * ```
 *
 * When the focus moves, borders and shadows change over [focusChangeMillis].
 *
 * Blur only shows behind see-through windows (a transparent terminal, or with an opacity
 * below 1). Fullscreen windows never get effects.
 */
data class Effects @JvmOverloads constructor(
    /** How round the corners are, in pixels (0 = square). */
    val cornerRadius: Int = 0,
    /** A frame around the window, or null. */
    val border: Border? = null,
    /** A soft shadow under the window, or null. */
    val shadow: Shadow? = null,
    /** Blur what is behind see-through parts of the window, or null. */
    val blur: Blur? = null,
    /** How solid the focused window is (1 = solid, 0 = invisible). */
    val opacity: Float = 1f,
    /** How solid the other windows are. */
    val inactiveOpacity: Float = opacity,
    /** The shadow of windows without the focus (null: the same as [shadow]; [Shadow.NONE]: none). */
    val inactiveShadow: Shadow? = null,
    /** How long border and shadow take to change when the focus moves (milliseconds; 0: at once). */
    val focusChangeMillis: Long = 150,
    /** How much darker windows without the focus are (0: not at all, 1: black). */
    val dimInactive: Float = 0f,
) {
    init {
        require(cornerRadius >= 0) { "cornerRadius must not be negative" }
        require(opacity in 0f..1f && inactiveOpacity in 0f..1f) { "opacity must be between 0 and 1" }
        require(focusChangeMillis in 0..5000) { "focusChangeMillis is 0 to 5000" }
        require(dimInactive in 0f..1f) { "dimInactive is 0 to 1, not $dimInactive" }
    }

    // For Java, where named arguments do not exist: Effects.NONE.withCorners(10).withShadow(new Shadow()).
    fun withCorners(radius: Int) = copy(cornerRadius = radius)
    fun withBorder(border: Border?) = copy(border = border)
    fun withShadow(shadow: Shadow?) = copy(shadow = shadow)
    fun withBlur(blur: Blur?) = copy(blur = blur)
    fun withOpacity(focused: Float, others: Float) = copy(opacity = focused, inactiveOpacity = others)
    fun withDimInactive(amount: Float) = copy(dimInactive = amount)

    companion object {
        /** No effects at all: windows look exactly as the programs drew them. */
        @JvmField val NONE = Effects()

        /** A common modern look to start from: round corners, a focus border, soft shadows. */
        @JvmField val MODERN = Effects(cornerRadius = 10, border = Border(), shadow = Shadow())
    }
}

/**
 * A frame [width] pixels wide around a window, filled by whether it has the focus: one
 * colour, a gradient, a turning gradient or a shader ([Paint]).
 */
data class Border @JvmOverloads constructor(
    val width: Int = 2,
    val focused: Paint = Paint.solid(Color.hex("#89b4fa")),
    val unfocused: Paint = Paint.solid(Color.hex("#45475a")),
) {
    /** With plain colours. */
    constructor(width: Int, focused: Color, unfocused: Color) : this(width, Paint.solid(focused), Paint.solid(unfocused))

    init {
        require(width > 0) { "a border must be at least 1 pixel wide" }
    }
}

/**
 * A shadow under a window, fading out over [softness] pixels, moved by [offsetX] and
 * [offsetY] (a light from above casts it a bit downwards).
 */
data class Shadow @JvmOverloads constructor(
    val softness: Int = 24,
    val offsetX: Int = 0,
    val offsetY: Int = 6,
    /** What it is filled with: a colour (a coloured shadow is a glow), a gradient, a shader. */
    val color: Paint = Paint.solid(Color(0f, 0f, 0f, 0.45f)),
    /** How much bigger than the window it is on each side, in pixels (negative: smaller). */
    val spread: Int = 0,
) {
    /** With a plain colour. */
    @JvmOverloads
    constructor(softness: Int, offsetX: Int, offsetY: Int, color: Color, spread: Int = 0) : this(softness, offsetX, offsetY, Paint.solid(color), spread)

    companion object {
        /** No shadow (for [Effects.inactiveShadow]: windows without the focus have none). */
        @JvmField val NONE = Shadow(1, 0, 0, Paint.solid(Color(0f, 0f, 0f, 0f)))
    }

    init {
        require(softness > 0) { "a shadow's softness must be at least 1 pixel" }
    }
}

/**
 * Frosted glass: what is behind a see-through window is blurred. [size] is how strong,
 * [passes] how smooth (1..6; more passes reach further and cost more).
 */
data class Blur @JvmOverloads constructor(
    val size: Int = 4,
    val passes: Int = 2,
    /** How the glass is toned: grain, contrast, brightness, vibrancy (BlurTone.HYPRLAND looks like Hyprland's). */
    val tone: dev.caulis.render.BlurTone = dev.caulis.render.BlurTone.NONE,
) {
    init {
        require(size > 0) { "blur size must be at least 1" }
        require(passes in 1..dev.caulis.render.MAX_BLUR_PASSES) { "blur passes must be between 1 and ${dev.caulis.render.MAX_BLUR_PASSES}" }
    }
}

/** Paints from files: shaders you edit while the compositor runs (see [Paint.Shader]). */
object Paints {
    /** A paint shader from [path] (`vec4 paint(vec2 at)`), used again as soon as it is saved. */
    @JvmStatic
    fun file(path: Path): Paint {
        val text = WatchedText.of(path)
        return Paint.Shader({ text.text }, path.fileName.toString())
    }
}
