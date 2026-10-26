package dev.caulis.screenshot

import dev.caulis.render.Color

/**
 * How the screenshot chooser looks: its toolbar's [background], [text] and [border], the
 * [accent] (the Capture button, the frame around a window or screen; [onAccent] is written on
 * it), its corners' [cornerRadius] (units; half its height makes a pill), the [font], and its
 * words in your language ([labels]: "Area", "Window", "Screen", "Pointer", "Timer", "Capture",
 * "%s s" → yours).
 */
data class ScreenshotStyle @JvmOverloads constructor(
    val background: Color = Color(0.11f, 0.118f, 0.141f, 0.925f),
    val text: Color = Color(0.94f, 0.95f, 0.965f),
    val dim: Color = Color(0.86f, 0.88f, 0.9f),
    val border: Color = Color(1f, 1f, 1f, 0.11f),
    val accent: Color = Color(0.24f, 0.55f, 0.99f),
    val onAccent: Color = Color(1f, 1f, 1f),
    val cornerRadius: Float = 18f,
    val font: String = java.awt.Font.SANS_SERIF,
    val labels: Map<String, String> = emptyMap(),
) {
    internal fun word(english: String): String = labels[english] ?: english
}

internal fun Color.awt(): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

internal fun Color.awt(alpha: Float): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
