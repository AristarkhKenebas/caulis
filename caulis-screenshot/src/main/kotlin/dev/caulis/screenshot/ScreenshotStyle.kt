package dev.caulis.screenshot

import dev.caulis.render.Color

/**
 * How the screenshot chooser looks: its toolbar's [background], [text] and [border], [dim]
 * text (a key's name in a tip), the [accent] (the Capture button, the frame around a window or
 * screen; [onAccent] is written on it), its corners' [cornerRadius] (units; half its height
 * makes a pill), the [font] and its sizes, how dark the screen gets around what is chosen
 * ([shade]), and its [words] in your language. Sizes are in desktop units.
 */
data class ScreenshotStyle @JvmOverloads constructor(
    val background: Color = Color(0.11f, 0.118f, 0.141f, 0.925f),
    val text: Color = Color(0.94f, 0.95f, 0.965f),
    val dim: Color = Color(0.94f, 0.95f, 0.965f, 0.5f),
    val border: Color = Color(1f, 1f, 1f, 0.11f),
    val accent: Color = Color(0.24f, 0.55f, 0.99f),
    val onAccent: Color = Color(1f, 1f, 1f),
    val cornerRadius: Float = 18f,
    val font: String = java.awt.Font.SANS_SERIF,
    /** The size of the tips' and labels' text (the size of the part chosen). */
    val fontSize: Float = 12f,
    /** The size of the Capture button's word (bold). */
    val captureFontSize: Float = 13f,
    /** How dark the screen gets around what is chosen (0: not at all, 1: black). */
    val shade: Float = 0.42f,
    /** The toolbar's shadow: its colour and how far it spreads. */
    val shadow: Color = Color(0f, 0f, 0f, 0.35f),
    val shadowSize: Float = 18f,
    /** The dots on a part's edges and corners (to drag them): their size, colour and rim. */
    val handleSize: Int = 9,
    val handle: Color = Color(1f, 1f, 1f),
    val handleRim: Color = Color(0.1f, 0.1f, 0.12f, 0.6f),
    /** The toolbar's height, a button's width (Capture is as wide as its word needs, with [capturePadding] each side). */
    val toolbarHeight: Int = 58,
    val buttonWidth: Int = 50,
    val capturePadding: Int = 30,
    /** How far above the bottom of the monitor (its usable part) the toolbar is. */
    val toolbarMargin: Int = 28,
    /** The room at the toolbar's ends, and between its groups of buttons (the kinds, the options, Capture). */
    val toolbarPadding: Int = 7,
    val groupGap: Int = 12,
    /** How far a button's tile is inside the toolbar (above and below), and its corners' radius. */
    val buttonInset: Int = 7,
    val buttonRadius: Float = 11f,
    /** The pills with a tip or the size of the part: their corners' radius and the room left and right of their text. */
    val labelRadius: Float = 8f,
    val labelPadding: Int = 11,
    /** Its words: in your language. */
    val words: ScreenshotWords = ScreenshotWords(),
)

/**
 * Every text the screenshots show, to put in your language (`%s` is replaced: a number, a
 * file). Key names in the tips come from [keyNames] (a key's own name otherwise: "A", "Tab").
 */
data class ScreenshotWords @JvmOverloads constructor(
    // The toolbar's buttons (their names in the tip above them).
    val area: String = "Area",
    val window: String = "Window",
    val screen: String = "Screen",
    val windows: String = "Windows, whole",
    val adjustArea: String = "Move and resize the area",
    val newArea: String = "A new area each time",
    val pointerIn: String = "Pointer: in the picture",
    val pointerOut: String = "Pointer: left out",
    /** The pointer button where the picture is put together from windows (no pointer there). */
    val pointerNotHere: String = "Pointer: not in a picture put together from windows",
    val timer: String = "Timer: %s s",
    val timerOff: String = "Timer: off",
    val capture: String = "Capture",
    val close: String = "Close",
    // Below what is chosen.
    val oneWindow: String = "1 window",
    val manyWindows: String = "%s windows",
    // Said when there is nothing to take yet.
    val dragFirst: String = "Drag to choose a part first",
    val pointFirst: String = "Point at a window first",
    val pickFirst: String = "Drag over windows or click them first",
    val noFocusedWindow: String = "No window has the focus",
    val hiddenWindow: String = "This window is hidden from screenshots",
    // The countdown, and the notice after.
    val countdown: String = "Screenshot in %s",
    val title: String = "Screenshot",
    val savedTo: String = "Saved to %s",
    val copied: String = "Copied",
    val notSaved: String = "The screenshot could not be saved: %s",
    /** Key names as the tips show them (by their name in xkb: "Return", "Escape", "space"). */
    val keyNames: Map<String, String> = mapOf("Return" to "Enter", "KP_Enter" to "Enter", "Escape" to "Esc", "space" to "Space"),
) {
    /** How a key (its xkb name) is shown in a tip. */
    fun keyName(key: String): String = keyNames[key] ?: if (key.length == 1) key.uppercase() else key
}

internal fun String.with(value: Any): String = replace("%s", value.toString())

internal fun Color.awt(): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

internal fun Color.awt(alpha: Float): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
