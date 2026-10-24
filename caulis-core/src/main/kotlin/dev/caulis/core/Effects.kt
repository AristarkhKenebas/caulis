package dev.caulis.core

import dev.caulis.render.Color

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
) {
    init {
        require(cornerRadius >= 0) { "cornerRadius must not be negative" }
        require(opacity in 0f..1f && inactiveOpacity in 0f..1f) { "opacity must be between 0 and 1" }
    }

    // For Java, where named arguments do not exist: Effects.NONE.withCorners(10).withShadow(new Shadow()).
    fun withCorners(radius: Int) = copy(cornerRadius = radius)
    fun withBorder(border: Border?) = copy(border = border)
    fun withShadow(shadow: Shadow?) = copy(shadow = shadow)
    fun withBlur(blur: Blur?) = copy(blur = blur)
    fun withOpacity(focused: Float, others: Float) = copy(opacity = focused, inactiveOpacity = others)

    companion object {
        /** No effects at all: windows look exactly as the programs drew them. */
        @JvmField val NONE = Effects()

        /** A common modern look to start from: round corners, a focus border, soft shadows. */
        @JvmField val MODERN = Effects(cornerRadius = 10, border = Border(), shadow = Shadow())
    }
}

/** A frame [width] pixels wide around a window, coloured by whether it has the focus. */
data class Border @JvmOverloads constructor(
    val width: Int = 2,
    val focused: Color = Color.hex("#89b4fa"),
    val unfocused: Color = Color.hex("#45475a"),
) {
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
    val color: Color = Color(0f, 0f, 0f, 0.45f),
) {
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
) {
    init {
        require(size > 0) { "blur size must be at least 1" }
        require(passes in 1..dev.caulis.render.MAX_BLUR_PASSES) { "blur passes must be between 1 and ${dev.caulis.render.MAX_BLUR_PASSES}" }
    }
}
