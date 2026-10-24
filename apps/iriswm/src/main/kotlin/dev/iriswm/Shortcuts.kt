package dev.iriswm

/**
 * irisWM's own key combinations. Keys that belong to a combination are not passed to
 * programs (neither the press nor its release).
 */
class Shortcuts(
    private val quit: () -> Unit,
    private val openTerminal: () -> Unit,
    /** Null where switching text consoles is impossible (nested mode). */
    private val switchConsole: ((Int) -> Unit)?,
) {
    private val held = HashSet<Int>()
    private val swallowed = HashSet<Int>()

    /** Returns true if the key was used by irisWM. */
    fun key(evdevCode: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            held -= evdevCode
            return swallowed.remove(evdevCode)
        }
        held += evdevCode
        val ctrl = KEY_LEFTCTRL in held || KEY_RIGHTCTRL in held
        val alt = KEY_LEFTALT in held || KEY_RIGHTALT in held
        val superKey = KEY_LEFTMETA in held || KEY_RIGHTMETA in held
        val action: (() -> Unit)? = when {
            ctrl && alt && evdevCode == KEY_BACKSPACE -> quit
            superKey && evdevCode == KEY_ENTER -> openTerminal
            ctrl && alt && switchConsole != null && consoleNumber(evdevCode) != null -> {
                val number = consoleNumber(evdevCode)!!
                { switchConsole.invoke(number) }
            }
            else -> null
        }
        if (action == null) return false
        swallowed += evdevCode
        action()
        return true
    }

    // F1..F10 are 59..68, F11 and F12 are 87 and 88 (Linux key codes).
    private fun consoleNumber(code: Int): Int? = when (code) {
        in 59..68 -> code - 58
        87 -> 11
        88 -> 12
        else -> null
    }

    private companion object {
        const val KEY_BACKSPACE = 14
        const val KEY_ENTER = 28
        const val KEY_LEFTCTRL = 29
        const val KEY_RIGHTCTRL = 97
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTALT = 100
        const val KEY_LEFTMETA = 125
        const val KEY_RIGHTMETA = 126
    }
}
