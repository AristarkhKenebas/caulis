package dev.caulis.screenshot

/** The chooser's toolbar buttons. */
enum class ChooserButton {
    /** The kinds (a group of their own): a part, a window, a monitor, several windows (over an overview only). */
    REGION, WINDOW, SCREEN, WINDOWS,

    /** How a part is chosen ([Screenshots.Selection]). */
    SELECTION,

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
 * far a drag must go to be one ([minDrag] units).
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
) {
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
