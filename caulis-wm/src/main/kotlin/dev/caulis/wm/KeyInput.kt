package dev.caulis.wm

/**
 * A key as [Caulis.onKey] gets it, before anything else does: its Linux code, its symbol's
 * name as xkb gives it now ("h", "H", "Return", "Super_L", "XF86AudioMute"), and the
 * modifiers held before and after it ([Modifiers] bits).
 */
class KeyInput(
    val evdevCode: Int,
    val pressed: Boolean,
    val keysym: String,
    val modifiers: Int,
    val modifiersBefore: Int,
    /** The screen is locked: only the laptop's special keys (XF86…) are told then. */
    val locked: Boolean,
    val timeMillis: Int,
) {
    /** True if this key is a modifier itself (Shift, Ctrl, Alt, AltGr, Super). */
    val isModifier: Boolean get() = Modifiers.of(evdevCode) != 0

    override fun toString() = "KeyInput($keysym ${if (pressed) "down" else "up"}, modifiers $modifiersBefore → $modifiers)"

    /** Modifier bits, as xkb numbers them. */
    object Modifiers {
        const val SHIFT = 1
        const val CTRL = 4
        const val ALT = 8
        const val LOGO = 64
        const val ALTGR = 128

        /** The modifier bit [evdevCode] holds (0: not a modifier). */
        @JvmStatic
        fun of(evdevCode: Int): Int = when (evdevCode) {
            42, 54 -> SHIFT
            29, 97 -> CTRL
            56 -> ALT
            100 -> ALTGR
            125, 126 -> LOGO
            else -> 0
        }
    }
}
