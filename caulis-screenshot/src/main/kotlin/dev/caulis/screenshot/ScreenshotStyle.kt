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
    /** The toolbar's button that clears the part chosen (shown while there is one). */
    val clearSelection: String = "Clear the selection",
) {
    // Caulis 0.1.1's constructor with its defaults left out: what Kotlin code built against it
    // calls — the defaults' mask and marker as Kotlin passes them.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    constructor(
        area: String?, window: String?, screen: String?, windows: String?, adjustArea: String?, newArea: String?, pointerIn: String?, pointerOut: String?, pointerNotHere: String?, timer: String?, timerOff: String?, capture: String?, close: String?, oneWindow: String?, manyWindows: String?, dragFirst: String?, pointFirst: String?, pickFirst: String?, noFocusedWindow: String?, hiddenWindow: String?, countdown: String?, title: String?, savedTo: String?, copied: String?, notSaved: String?, keyNames: Map<String, String>?, defaults: Int,
        @Suppress("UNUSED_PARAMETER") marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        if (defaults and (1 shl 0) != 0 || area == null) d.area else area,
        if (defaults and (1 shl 1) != 0 || window == null) d.window else window,
        if (defaults and (1 shl 2) != 0 || screen == null) d.screen else screen,
        if (defaults and (1 shl 3) != 0 || windows == null) d.windows else windows,
        if (defaults and (1 shl 4) != 0 || adjustArea == null) d.adjustArea else adjustArea,
        if (defaults and (1 shl 5) != 0 || newArea == null) d.newArea else newArea,
        if (defaults and (1 shl 6) != 0 || pointerIn == null) d.pointerIn else pointerIn,
        if (defaults and (1 shl 7) != 0 || pointerOut == null) d.pointerOut else pointerOut,
        if (defaults and (1 shl 8) != 0 || pointerNotHere == null) d.pointerNotHere else pointerNotHere,
        if (defaults and (1 shl 9) != 0 || timer == null) d.timer else timer,
        if (defaults and (1 shl 10) != 0 || timerOff == null) d.timerOff else timerOff,
        if (defaults and (1 shl 11) != 0 || capture == null) d.capture else capture,
        if (defaults and (1 shl 12) != 0 || close == null) d.close else close,
        if (defaults and (1 shl 13) != 0 || oneWindow == null) d.oneWindow else oneWindow,
        if (defaults and (1 shl 14) != 0 || manyWindows == null) d.manyWindows else manyWindows,
        if (defaults and (1 shl 15) != 0 || dragFirst == null) d.dragFirst else dragFirst,
        if (defaults and (1 shl 16) != 0 || pointFirst == null) d.pointFirst else pointFirst,
        if (defaults and (1 shl 17) != 0 || pickFirst == null) d.pickFirst else pickFirst,
        if (defaults and (1 shl 18) != 0 || noFocusedWindow == null) d.noFocusedWindow else noFocusedWindow,
        if (defaults and (1 shl 19) != 0 || hiddenWindow == null) d.hiddenWindow else hiddenWindow,
        if (defaults and (1 shl 20) != 0 || countdown == null) d.countdown else countdown,
        if (defaults and (1 shl 21) != 0 || title == null) d.title else title,
        if (defaults and (1 shl 22) != 0 || savedTo == null) d.savedTo else savedTo,
        if (defaults and (1 shl 23) != 0 || copied == null) d.copied else copied,
        if (defaults and (1 shl 24) != 0 || notSaved == null) d.notSaved else notSaved,
        if (defaults and (1 shl 25) != 0 || keyNames == null) d.keyNames else keyNames,
    )

    // Caulis 0.1.1's copy (code built against it calls it).
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    fun copy(area: String = this.area, window: String = this.window, screen: String = this.screen, windows: String = this.windows, adjustArea: String = this.adjustArea, newArea: String = this.newArea, pointerIn: String = this.pointerIn, pointerOut: String = this.pointerOut, pointerNotHere: String = this.pointerNotHere, timer: String = this.timer, timerOff: String = this.timerOff, capture: String = this.capture, close: String = this.close, oneWindow: String = this.oneWindow, manyWindows: String = this.manyWindows, dragFirst: String = this.dragFirst, pointFirst: String = this.pointFirst, pickFirst: String = this.pickFirst, noFocusedWindow: String = this.noFocusedWindow, hiddenWindow: String = this.hiddenWindow, countdown: String = this.countdown, title: String = this.title, savedTo: String = this.savedTo, copied: String = this.copied, notSaved: String = this.notSaved, keyNames: Map<String, String> = this.keyNames): ScreenshotWords =
        ScreenshotWords(area, window, screen, windows, adjustArea, newArea, pointerIn, pointerOut, pointerNotHere, timer, timerOff, capture, close, oneWindow, manyWindows, dragFirst, pointFirst, pickFirst, noFocusedWindow, hiddenWindow, countdown, title, savedTo, copied, notSaved, keyNames, clearSelection)

    /** How a key (its xkb name) is shown in a tip. */
    fun keyName(key: String): String = keyNames[key] ?: if (key.length == 1) key.uppercase() else key
}

// The words by default (for the constructor kept for Caulis 0.1.1).
private val d by lazy { ScreenshotWords() }

internal fun String.with(value: Any): String = replace("%s", value.toString())

internal fun Color.awt(): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

internal fun Color.awt(alpha: Float): java.awt.Color = java.awt.Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
