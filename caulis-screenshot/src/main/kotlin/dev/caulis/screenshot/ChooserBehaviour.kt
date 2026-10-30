package dev.caulis.screenshot

/** The chooser's toolbar buttons. */
enum class ChooserButton {
    /** The kinds (a group of their own): a part, a window, a monitor, several windows (over an overview only). */
    REGION, WINDOW, SCREEN, WINDOWS,

    /** How a part is chosen ([Screenshots.Selection]). */
    SELECTION,

    /** Clears the part chosen (shown only while there is one). */
    CLEAR,

    /** The pointer in the picture or not. */
    POINTER,

    /** The timer: the next of [ChooserBehaviour.timerSteps]. */
    TIMER,

    /** Takes it (with its word on it). */
    TAKE,

    /** Closes the chooser. */
    CLOSE;

    /** One of the kinds. */
    val isKind: Boolean get() = this == REGION || this == WINDOW || this == SCREEN || this == WINDOWS
}

/**
 * How the chooser behaves: its toolbar's [buttons] (in this order; [ChooserButton.WINDOWS] shows
 * only over an overview), the timer's [timerSteps] (seconds, 0: off), its [keys], how quickly it
 * comes ([appearMillis], the toolbar rising [rise] units: the desktop's animation "screenshot", off
 * unless animations are on), what counts as a double click
 * ([doubleClickMillis]), how near an edge of a part the pointer grabs it ([grab] units) and how
 * far a drag must go to be one ([minDrag] units). It starts choosing a part (a window is
 * chosen by its key or button): with [rememberKind] it starts with the kind used last instead,
 * and with [clickPicksWindow] a click (not a drag) while choosing a part takes the window there.
 *
 * With [rememberSelection] the part chosen last is there again when it opens (as on a Mac):
 * Enter or Capture takes it at once, or drag it, or clear it (the toolbar's clear button, or
 * Escape with [escapeClearsSelection]). It is dropped when the monitors change, and kept across
 * restarts with [rememberAcrossRestarts] ([Screenshots.selectionFile]).
 */
data class ChooserBehaviour @JvmOverloads constructor(
    val buttons: List<ChooserButton> = ChooserButton.entries,
    val timerSteps: List<Int> = listOf(0, 3, 5, 10),
    val keys: ChooserKeys = ChooserKeys(),
    val appearMillis: Long = 170,
    val rise: Int = 16,
    val doubleClickMillis: Long = 400,
    val grab: Int = 7,
    val minDrag: Int = 4,
    /** It starts with the kind used last (a window after a window): false starts with [Screenshots.kind] each time. */
    val rememberKind: Boolean = false,
    /** A click without a drag, while choosing a part, makes the window under it the part (false: nothing chosen). */
    val clickPicksWindow: Boolean = false,
    /** The part chosen last is shown again when it opens (while the monitors are the same); false: none each time. */
    val rememberSelection: Boolean = true,
    /** With [rememberSelection]: the part is kept across restarts too (in [Screenshots.selectionFile]). */
    val rememberAcrossRestarts: Boolean = false,
    /**
     * Escape first clears a part shown (it is not offered again), a second Escape closes; false:
     * Escape closes at once and the part is forgotten. (The close button and a right click
     * close at once and keep it.)
     */
    val escapeClearsSelection: Boolean = true,
) {
    // Caulis 0.1.1's constructor with its defaults left out: what Kotlin code built against it
    // calls — the defaults' mask and marker as Kotlin passes them.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    constructor(
        buttons: List<ChooserButton>?, timerSteps: List<Int>?, keys: ChooserKeys?, appearMillis: Long, rise: Int, doubleClickMillis: Long,
        grab: Int, minDrag: Int, rememberKind: Boolean, clickPicksWindow: Boolean, defaults: Int,
        @Suppress("UNUSED_PARAMETER") marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        if (defaults and 1 != 0 || buttons == null) ChooserButton.entries else buttons,
        if (defaults and 2 != 0 || timerSteps == null) listOf(0, 3, 5, 10) else timerSteps,
        if (defaults and 4 != 0 || keys == null) ChooserKeys() else keys,
        if (defaults and 8 != 0) 170 else appearMillis,
        if (defaults and 16 != 0) 16 else rise,
        if (defaults and 32 != 0) 400 else doubleClickMillis,
        if (defaults and 64 != 0) 7 else grab,
        if (defaults and 128 != 0) 4 else minDrag,
        defaults and 256 == 0 && rememberKind,
        defaults and 512 == 0 && clickPicksWindow,
    )

    // Caulis 0.1.1's copy (code built against it calls it).
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    fun copy(
        buttons: List<ChooserButton> = this.buttons, timerSteps: List<Int> = this.timerSteps, keys: ChooserKeys = this.keys,
        appearMillis: Long = this.appearMillis, rise: Int = this.rise, doubleClickMillis: Long = this.doubleClickMillis,
        grab: Int = this.grab, minDrag: Int = this.minDrag, rememberKind: Boolean = this.rememberKind, clickPicksWindow: Boolean = this.clickPicksWindow,
    ): ChooserBehaviour = ChooserBehaviour(
        buttons, timerSteps, keys, appearMillis, rise, doubleClickMillis, grab, minDrag, rememberKind, clickPicksWindow,
        rememberSelection, rememberAcrossRestarts, escapeClearsSelection,
    )

    init {
        require(timerSteps.isNotEmpty() && timerSteps.all { it >= 0 }) { "the timer's steps are seconds, 0 or more (at least one): $timerSteps" }
        require(appearMillis >= 0) { "appearMillis is 0 or more, not $appearMillis" }
        require(doubleClickMillis >= 0) { "doubleClickMillis is 0 or more, not $doubleClickMillis" }
        require(grab >= 0) { "grab is 0 or more units, not $grab" }
        require(minDrag >= 0) { "minDrag is 0 or more units, not $minDrag" }
    }
}

/**
 * The chooser's keys, by the names xkb gives them ("a", "Return", "Escape", "space", "Tab"),
 * as shortcuts see them: a letter is the key that types it in your layout (on AZERTY the
 * key of "a" is A, on Dvorak too), on a non-Latin layout the one of the first Latin layout.
 * The tips name the first of each. (The arrow keys move a part: with Shift by ten, with Ctrl
 * they resize it.)
 */
data class ChooserKeys @JvmOverloads constructor(
    val area: List<String> = listOf("a", "r"),
    val window: List<String> = listOf("w"),
    val screen: List<String> = listOf("s"),
    val windows: List<String> = listOf("g"),
    val selection: List<String> = listOf("m"),
    val pointer: List<String> = listOf("p"),
    val timer: List<String> = listOf("t"),
    val capture: List<String> = listOf("Return", "KP_Enter", "space"),
    val close: List<String> = listOf("Escape"),
    /** The next kind (with Shift the one before). */
    val nextKind: List<String> = listOf("Tab"),
    /** With Ctrl: only copies it (not saved). */
    val copy: List<String> = listOf("c"),
    /** With Ctrl, over an overview: all the windows. */
    val all: List<String> = listOf("a"),
)

internal fun List<String>.has(name: String?): Boolean = name != null && any { it.equals(name, ignoreCase = it.length == 1) }
