package dev.caulis.core.input

/**
 * Key combinations of the compositor itself, written the way people say them:
 *
 * ```
 * val keys = KeyBindings()
 * keys.bind("Super+Return") { launch("foot") }
 * keys.bind("Ctrl+Alt+BackSpace") { quit() }
 * keys.bind("Alt+Tab") { desktop.cycleFocus() }
 * ```
 *
 * Feed every key press and release to [key]; it returns true when a combination was
 * used. Such keys are not passed to programs — neither the press nor its release.
 *
 * Keys are physical (Linux key codes), so combinations work the same with every
 * keyboard layout (Super+Q stays on the same key with a Russian layout).
 */
class KeyBindings {

    private class Binding(val modifiers: Set<Modifier>, val key: Int, val action: () -> Unit)

    enum class Modifier(vararg val codes: Int) {
        CTRL(29, 97), ALT(56, 100), SHIFT(42, 54), SUPER(125, 126);

        companion object {
            fun of(code: Int): Modifier? = entries.firstOrNull { code in it.codes }
        }
    }

    private val bindings = ArrayList<Binding>()
    private val held = LinkedHashSet<Int>()
    private val swallowed = HashSet<Int>()

    /** Runs [action] when [combination] (e.g. "Super+Shift+Q") is pressed. */
    fun bind(combination: String, action: () -> Unit) {
        val (modifiers, key) = parse(combination)
        bindings += Binding(modifiers, key, action)
    }

    /** True while [modifier] is held down (e.g. for Super+drag to move windows). */
    fun isHeld(modifier: Modifier): Boolean = held.any { it in modifier.codes }

    /** The modifiers held down now. */
    fun heldModifiers(): Set<Modifier> = held.mapNotNull(Modifier::of).toSet()

    /** Handles a key; returns true if it belonged to a combination (do not pass it on). */
    fun key(evdevCode: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            held -= evdevCode
            return swallowed.remove(evdevCode)
        }
        held += evdevCode
        val active = heldModifiers()
        // Exact match: Super+Q does not also fire for Super+Shift+Q.
        val binding = bindings.lastOrNull { it.key == evdevCode && it.modifiers == active } ?: return false
        swallowed += evdevCode
        binding.action()
        return true
    }

    /** Forgets held keys (e.g. after switching to another terminal and back). */
    fun releaseAll() {
        held.clear()
        swallowed.clear()
    }

    companion object {
        private val MODIFIER_NAMES = mapOf(
            "ctrl" to Modifier.CTRL, "control" to Modifier.CTRL, "alt" to Modifier.ALT,
            "shift" to Modifier.SHIFT, "super" to Modifier.SUPER, "win" to Modifier.SUPER, "logo" to Modifier.SUPER,
        )

        // Linux key codes (<linux/input-event-codes.h>) of the keys people bind.
        private val KEYS: Map<String, Int> = buildMap {
            "QWERTYUIOP".forEachIndexed { i, c -> put(c.toString(), 16 + i) }
            "ASDFGHJKL".forEachIndexed { i, c -> put(c.toString(), 30 + i) }
            "ZXCVBNM".forEachIndexed { i, c -> put(c.toString(), 44 + i) }
            "1234567890".forEachIndexed { i, c -> put(c.toString(), 2 + i) }
            for (n in 1..10) put("F$n", 58 + n)
            put("F11", 87); put("F12", 88)
            put("ESCAPE", 1); put("ESC", 1); put("BACKSPACE", 14); put("TAB", 15)
            put("RETURN", 28); put("ENTER", 28); put("SPACE", 57)
            put("MINUS", 12); put("EQUAL", 13); put("GRAVE", 41)
            put("UP", 103); put("LEFT", 105); put("RIGHT", 106); put("DOWN", 108)
            put("HOME", 102); put("END", 107); put("PAGEUP", 104); put("PAGEDOWN", 109)
            put("INSERT", 110); put("DELETE", 111); put("PRINT", 99)
        }

        /**
         * The modifiers and key code of [combination] ("Super+Shift+Q"); throws with a
         * plain message when a name is unknown.
         */
        fun parse(combination: String): Pair<Set<Modifier>, Int> {
            val parts = combination.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "Empty key combination" }
            val key = keyCode(parts.last()) ?: throw IllegalArgumentException("Unknown key \"${parts.last()}\" in \"$combination\"")
            return modifiers(parts.dropLast(1).joinToString("+"), combination) to key
        }

        /** The modifiers of [text] ("Super+Shift"; "" = none); throws when a name is unknown. */
        fun modifiers(text: String, whole: String = text): Set<Modifier> =
            text.split('+').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
                MODIFIER_NAMES[name.lowercase()] ?: throw IllegalArgumentException("Unknown modifier \"$name\" in \"$whole\"")
            }.toSet()

        /** The key code for a name like "Q", "F3", "Return"; null if unknown. */
        fun keyCode(name: String): Int? = KEYS[name.uppercase()]
    }
}
