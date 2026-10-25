package dev.caulis.core.input

/**
 * Key combinations of the compositor itself, written the way people say them (case,
 * spaces and the order do not matter, see [parse]):
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

    /** Runs [action] when [combination] (e.g. "Super+Shift+Q", see [parse]) is pressed. */
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
        // Every way people write a modifier (compared without case, "_" and "-").
        private val MODIFIER_NAMES: Map<String, Modifier> = buildMap {
            for (name in listOf("ctrl", "control", "ctl", "strg", "ctrlr", "lctrl", "rctrl")) put(name, Modifier.CTRL)
            for (name in listOf("alt", "mod1", "option", "opt", "lalt", "ralt", "altl", "altr")) put(name, Modifier.ALT)
            for (name in listOf("shift", "shft", "lshift", "rshift", "shiftl", "shiftr")) put(name, Modifier.SHIFT)
            for (name in listOf("super", "win", "windows", "logo", "mod4", "meta", "cmd", "command", "os", "lsuper", "rsuper", "superl", "superr")) put(name, Modifier.SUPER)
        }

        // Linux key codes (<linux/input-event-codes.h>) by every name people give them
        // (compared without case, "_" and "-"; X11 key names like "XF86AudioMute" work too).
        private val KEYS: Map<String, Int> = buildMap {
            fun names(code: Int, vararg names: String) = names.forEach { put(normalize(it), code) }
            "qwertyuiop".forEachIndexed { i, c -> names(16 + i, c.toString()) }
            "asdfghjkl".forEachIndexed { i, c -> names(30 + i, c.toString()) }
            "zxcvbnm".forEachIndexed { i, c -> names(44 + i, c.toString()) }
            // The same keys with a Russian layout: keys are physical, Super+Й is Super+Q.
            "йцукенгшщзхъ".forEachIndexed { i, c -> names(16 + i, c.toString()) }
            "фывапролджэ".forEachIndexed { i, c -> names(30 + i, c.toString()) }
            "ячсмитьбю".forEachIndexed { i, c -> names(44 + i, c.toString()) }
            names(41, "ё")
            "1234567890".forEachIndexed { i, c -> names(2 + i, c.toString()) }
            for (n in 1..10) names(58 + n, "F$n")
            names(87, "F11"); names(88, "F12")
            for (n in 13..24) names(170 + n, "F$n")
            names(1, "Escape", "Esc")
            names(14, "BackSpace", "Bksp", "Back")
            names(15, "Tab")
            names(28, "Return", "Enter", "Ret")
            names(57, "Space", "Spc")
            names(12, "Minus", "-")
            names(13, "Equal", "Equals", "=")
            names(26, "BracketLeft", "LeftBracket", "[")
            names(27, "BracketRight", "RightBracket", "]")
            names(39, "Semicolon", ";")
            names(40, "Apostrophe", "Quote", "'")
            names(41, "Grave", "Backtick", "`", "Tilde", "~")
            names(43, "Backslash", "\\")
            names(51, "Comma", ",")
            names(52, "Period", "Dot", ".")
            names(53, "Slash", "/")
            names(103, "Up", "ArrowUp")
            names(105, "Left", "ArrowLeft")
            names(106, "Right", "ArrowRight")
            names(108, "Down", "ArrowDown")
            names(102, "Home")
            names(107, "End")
            names(104, "PageUp", "PgUp", "Prior")
            names(109, "PageDown", "PgDn", "PgDown")
            names(110, "Insert", "Ins")
            names(111, "Delete", "Del")
            names(99, "Print", "PrintScreen", "PrtSc", "PrtScr", "SysRq")
            names(119, "Pause", "Break")
            names(70, "ScrollLock")
            names(58, "CapsLock", "Caps")
            names(127, "Menu", "Compose")
            // The number block.
            "7894561230".toList().zip(listOf(71, 72, 73, 75, 76, 77, 79, 80, 81, 82)).forEach { (c, code) -> names(code, "KP$c", "KP_$c", "Num$c", "Numpad$c") }
            names(96, "KPEnter", "NumpadEnter")
            names(78, "KPAdd", "KPPlus", "NumpadPlus")
            names(74, "KPSubtract", "KPMinus", "NumpadMinus")
            names(55, "KPMultiply", "KPAsterisk", "NumpadMultiply")
            names(98, "KPDivide", "KPSlash", "NumpadDivide")
            names(83, "KPDecimal", "KPDot", "NumpadDot")
            // Media keys.
            names(113, "XF86AudioMute", "Mute", "AudioMute")
            names(114, "XF86AudioLowerVolume", "VolumeDown", "AudioLowerVolume")
            names(115, "XF86AudioRaiseVolume", "VolumeUp", "AudioRaiseVolume")
            names(248, "XF86AudioMicMute", "MicMute")
            names(164, "XF86AudioPlay", "XF86AudioPause", "Play", "PlayPause")
            names(163, "XF86AudioNext", "Next", "NextSong")
            names(165, "XF86AudioPrev", "Prev", "Previous", "PreviousSong")
            names(166, "XF86AudioStop", "Stop")
            names(224, "XF86MonBrightnessDown", "BrightnessDown")
            names(225, "XF86MonBrightnessUp", "BrightnessUp")
        }

        // "Page_Up", "page-up", "PAGEUP" -> "pageup" (a lone "-" or "_" stays itself).
        private fun normalize(name: String): String =
            if (name.length == 1) name.lowercase() else name.lowercase().filter { it != '_' && it != '-' && it != ' ' }

        // The parts of a combination: "Super+Shift+Q", "super shift q", "SUPER + q".
        private fun parts(text: String): List<String> {
            val parts = text.split('+', ' ', '\t').filter { it.isNotEmpty() }.toMutableList()
            // "Super++" or "Super+ +" mean the + key itself? There is none on its own; say so below.
            if (text.trimEnd().endsWith("++")) parts += "+"
            return parts
        }

        /**
         * The modifiers and key code of [combination] — written however people write it:
         * "Super+Shift+Q", "super shift q", "wIn+S", "Q+Super", "mod4+Return", "Super+Й"
         * (the same key as Super+Q). Throws with a plain message (and a guess at what was
         * meant) when a name is unknown.
         */
        fun parse(combination: String): Pair<Set<Modifier>, Int> = parse(parts(combination), combination)

        /** The same with the parts given one by one: `parse(listOf("super", "shift", "q"))`. */
        fun parse(parts: List<String>, whole: String = parts.joinToString("+")): Pair<Set<Modifier>, Int> {
            require(parts.any { it.isNotBlank() }) { "Empty key combination" }
            val modifiers = LinkedHashSet<Modifier>()
            val keys = ArrayList<Pair<String, Int>>()
            for (part in parts.flatMap(::parts)) {
                val name = normalize(part)
                val modifier = MODIFIER_NAMES[name]
                if (modifier != null) {
                    modifiers += modifier
                    continue
                }
                val code = KEYS[name] ?: throw IllegalArgumentException("Unknown key \"$part\" in \"$whole\"${guess(name)}")
                keys += part to code
            }
            return when (keys.size) {
                1 -> modifiers to keys.single().second
                0 -> throw IllegalArgumentException("\"$whole\" has no key besides the modifiers (e.g. \"$whole+Q\")")
                else -> throw IllegalArgumentException("\"$whole\" has more than one key (${keys.joinToString { it.first }}); a combination is modifiers and one key")
            }
        }

        /** The modifiers of [text] ("Super+Shift", "super shift"; "" = none); throws when a name is unknown. */
        fun modifiers(text: String, whole: String = text): Set<Modifier> =
            parts(text).map { part ->
                val name = normalize(part)
                MODIFIER_NAMES[name] ?: throw IllegalArgumentException(
                    if (name in KEYS) "\"$part\" in \"$whole\" is a key, not a modifier (Super, Ctrl, Alt, Shift)"
                    else "Unknown modifier \"$part\" in \"$whole\"${guess(name)}",
                )
            }.toSet()

        /** The key code for a name like "Q", "F3", "Return", "page_up"; null if unknown. */
        fun keyCode(name: String): Int? = KEYS[normalize(name)]

        // " (did you mean Return?)" for a name close to a known one.
        private fun guess(name: String): String {
            if (name.length < 3) return ""
            val known = MODIFIER_NAMES.keys + KEYS.keys
            val best = known.filter { it.length > 1 }.minByOrNull { distance(name, it) } ?: return ""
            return if (distance(name, best) <= maxOf(1, name.length / 4)) " (did you mean ${spelled(best)}?)" else ""
        }

        private fun spelled(name: String): String =
            SPELLED.firstOrNull { normalize(it) == name } ?: name.replaceFirstChar(Char::uppercase)

        // How the guessed names are written back to the user.
        private val SPELLED = listOf(
            "Super", "Ctrl", "Alt", "Shift", "Return", "Escape", "BackSpace", "Tab", "Space", "Delete", "Insert", "Home", "End",
            "PageUp", "PageDown", "Print", "Left", "Right", "Up", "Down", "Grave", "Minus", "Equal", "Comma", "Period", "Slash",
            "XF86AudioMute", "XF86AudioRaiseVolume", "XF86AudioLowerVolume", "XF86AudioPlay", "XF86AudioNext", "XF86AudioPrev",
            "XF86MonBrightnessUp", "XF86MonBrightnessDown",
        )

        // Edit distance (letters added, left out, changed or swapped).
        private fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
