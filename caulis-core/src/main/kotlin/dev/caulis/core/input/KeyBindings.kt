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
 * keyboard layout (Super+Q stays on the same key with a Russian layout). Mouse buttons
 * ("Super+MouseSide", "mouse:276"), the wheel ("Super+WheelUp") and a drawing tablet's pad
 * buttons ("Pad1", the first) are keys too, and a modifier alone ("Super") is a tap of it:
 * pressed and let go with nothing else in between.
 */
class KeyBindings {

    /**
     * How a combination behaves: [repeat] while held (volume keys), runs on [release]
     * instead of on the press, works [whileLocked] (the compositor decides that), and
     * [description] says what it does (for lists of keys).
     */
    data class Options(
        val repeat: Boolean = false,
        val release: Boolean = false,
        val whileLocked: Boolean = false,
        val description: String? = null,
    )

    // key = null: a tap of the modifiers alone.
    private class Binding(val modifiers: Set<Modifier>, val key: Int?, val options: Options, val combination: String, val action: () -> Unit)

    enum class Modifier(vararg val codes: Int) {
        CTRL(29, 97), ALT(56, 100), SHIFT(42, 54), SUPER(125, 126);

        companion object {
            fun of(code: Int): Modifier? = entries.firstOrNull { code in it.codes }
        }
    }

    /**
     * Which modifier a key is, if any. By default the keys' physical places (left and right
     * Ctrl, Alt, Shift, Super); give `seat::modifierOfKey` to follow the user's keyboard
     * options instead (Caps Lock made Super by `caps:super`, Alt and Super swapped).
     */
    var modifierOf: (evdevCode: Int) -> Modifier? = { Modifier.of(it) }

    private fun modifier(code: Int): Modifier? = try {
        modifierOf(code)
    } catch (e: Exception) {
        Modifier.of(code)
    }

    private val bindings = ArrayList<Binding>()
    // Keys held down, each with the modifier it was when pressed (kept: a layout switch or a
    // new keymap while it is held must not turn a held Alt into "no modifier" and back, which
    // left it held for good, or lost it).
    private val held = LinkedHashMap<Int, Modifier?>()
    private val swallowed = HashSet<Int>()

    // Combinations that run when their key is let go, by key.
    private val onRelease = HashMap<Int, Binding>()

    // A modifier alone held so far, with nothing else pressed since (a tap if it is let go now).
    private var tapCandidate: Set<Modifier>? = null

    // Repeating the combination held now.
    private var repeating: AutoCloseable? = null
    private var repeatingKey = 0

    /**
     * Runs `task` once after a delay in milliseconds; set by whoever has a loop. Needed for
     * [Options.repeat]; without it combinations do not repeat.
     */
    var schedule: ((Long, () -> Unit) -> AutoCloseable)? = null

    /** Key repeat: delay before the first repeat, and repeats per second. */
    var repeatDelay = Seat.REPEAT_DELAY
    var repeatRate = Seat.REPEAT_RATE

    /** Runs [action] when [combination] (e.g. "Super+Shift+Q", "Super", "Super+WheelUp", see [parse]) is pressed. */
    fun bind(combination: String, action: () -> Unit) = bind(combination, Options(), action)

    /** The same, behaving as [options] say. */
    fun bind(combination: String, options: Options, action: () -> Unit) {
        val (modifiers, key) = parseAllowingTap(combination)
        bindings += Binding(modifiers, key, options, combination, action)
    }

    /** The combinations with a description: combination → what it does. */
    fun described(): List<Pair<String, String>> = bindings.mapNotNull { b -> b.options.description?.let { b.combination to it } }

    /** True if some combination is the key or button [code] with the modifiers held now (wheel: [WHEEL_UP], ...). */
    fun has(code: Int): Boolean {
        val active = heldModifiers()
        return bindings.any { it.key == code && it.modifiers == active }
    }

    /** True while [modifier] is held down (e.g. for Super+drag to move windows). */
    fun isHeld(modifier: Modifier): Boolean = modifier in held.values

    /** The modifiers held down now. */
    fun heldModifiers(): Set<Modifier> = held.values.filterNotNullTo(HashSet())

    /**
     * Handles a key or mouse button (Linux codes); returns true if it belonged to a
     * combination (do not pass it on).
     */
    fun key(evdevCode: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            val wasModifiers = heldModifiers()
            val wasModifier = if (evdevCode in held) held.remove(evdevCode) else modifier(evdevCode)
            if (evdevCode == repeatingKey) stopRepeating()
            // A modifier alone pressed and let go: its tap.
            val tap = tapCandidate
            tapCandidate = null
            if (tap != null && wasModifier != null) {
                bindings.lastOrNull { it.key == null && it.modifiers == wasModifiers }?.let { it.action(); return false }
            }
            onRelease.remove(evdevCode)?.let { it.action(); swallowed.remove(evdevCode); return true }
            return swallowed.remove(evdevCode)
        }
        val isModifier = modifier(evdevCode)
        held[evdevCode] = isModifier
        val active = heldModifiers()
        tapCandidate = if (isModifier != null && held.values.all { it != null }) active else null
        if (isModifier != null) return false
        // Exact match: Super+Q does not also fire for Super+Shift+Q.
        val binding = bindings.lastOrNull { it.key == evdevCode && it.modifiers == active } ?: return false
        swallowed += evdevCode
        when {
            binding.options.release -> onRelease[evdevCode] = binding
            binding.options.repeat -> {
                binding.action()
                startRepeating(evdevCode, binding)
            }
            else -> binding.action()
        }
        return true
    }

    /**
     * The wheel turned one step ([WHEEL_UP], [WHEEL_DOWN], [WHEEL_LEFT], [WHEEL_RIGHT]);
     * returns true if it belonged to a combination.
     */
    fun wheel(direction: Int): Boolean {
        tapCandidate = null
        val active = heldModifiers()
        val binding = bindings.lastOrNull { it.key == direction && it.modifiers == active } ?: return false
        binding.action()
        return true
    }

    /** Something else happened while modifiers were held (a click, a scroll): no tap then. */
    fun interrupted() {
        tapCandidate = null
    }

    private fun startRepeating(code: Int, binding: Binding) {
        val schedule = schedule ?: return
        stopRepeating()
        repeatingKey = code
        fun next(delay: Long) {
            repeating = schedule(delay) {
                if (repeatingKey == code && code in held.keys) {
                    binding.action()
                    next(1000L / repeatRate.coerceAtLeast(1))
                }
            }
        }
        if (repeatRate > 0) next(repeatDelay.toLong())
    }

    private fun stopRepeating() {
        repeating?.close()
        repeating = null
        repeatingKey = 0
    }

    /** Forgets every combination (keys held down stay held). */
    fun clear() {
        bindings.clear()
        onRelease.clear()
        stopRepeating()
    }

    /**
     * [evdevCode] was let go, but someone else took the release (an onKey handler): it is no
     * longer held, its combination stops repeating, and nothing runs. Without this a held
     * modifier stayed held for good, and every combination without it stopped working.
     */
    fun forget(evdevCode: Int) {
        held -= evdevCode
        swallowed -= evdevCode
        onRelease -= evdevCode
        if (evdevCode == repeatingKey) stopRepeating()
        tapCandidate = null
    }

    /** Forgets held keys (e.g. after switching to another terminal and back). */
    fun releaseAll() {
        held.clear()
        swallowed.clear()
        onRelease.clear()
        tapCandidate = null
        stopRepeating()
    }

    companion object {
        // Every way people write a modifier (compared without case, "_" and "-").
        private val MODIFIER_NAMES: Map<String, Modifier> = buildMap {
            for (name in listOf("ctrl", "control", "ctl", "strg", "ctrlr", "lctrl", "rctrl")) put(name, Modifier.CTRL)
            for (name in listOf("alt", "mod1", "option", "opt", "lalt", "ralt", "altl", "altr")) put(name, Modifier.ALT)
            for (name in listOf("shift", "shft", "lshift", "rshift", "shiftl", "shiftr")) put(name, Modifier.SHIFT)
            for (name in listOf("super", "win", "windows", "logo", "mod4", "meta", "cmd", "command", "os", "lsuper", "rsuper", "superl", "superr")) put(name, Modifier.SUPER)
        }

        // Each key code's first name, as written above (for messages: "XF86Favorites").
        private val FIRST_NAMES = HashMap<Int, String>()

        /** The usual name of the Linux key code [code] ("XF86AudioMute", "F7"), or null. */
        @JvmStatic
        fun nameOf(code: Int): String? = FIRST_NAMES[code]

        // Linux key codes (<linux/input-event-codes.h>) by every name people give them
        // (compared without case, "_" and "-"; X11 key names like "XF86AudioMute" work too).
        private val KEYS: Map<String, Int> = buildMap {
            fun names(code: Int, vararg names: String) {
                FIRST_NAMES.putIfAbsent(code, names.first())
                names.forEach { put(normalize(it), code) }
            }
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
            // Laptop keys (Fn with F1..F12 on most laptops; the names X11 gives them).
            names(227, "XF86Display", "SwitchVideoMode", "Display", "Projector")
            names(530, "XF86TouchpadToggle", "TouchpadToggle")
            names(531, "XF86TouchpadOn", "TouchpadOn")
            names(532, "XF86TouchpadOff", "TouchpadOff")
            names(634, "XF86SelectiveScreenshot", "SelectiveScreenshot", "ScreenshotKey")
            names(364, "XF86Favorites", "Favorites", "Star")
            names(247, "XF86RFKill", "RFKill", "AirplaneMode")
            names(238, "XF86WLAN", "WLAN", "WiFi")
            names(237, "XF86Bluetooth", "Bluetooth")
            names(212, "XF86WebCam", "Camera")
            names(140, "XF86Calculator", "Calculator")
            names(148, "XF86Launch1", "Prog1")
            names(149, "XF86Launch2", "Prog2")
            names(202, "XF86Launch3", "Prog3")
            names(203, "XF86Launch4", "Prog4")
            names(142, "XF86Sleep", "Sleep")
            names(116, "XF86PowerOff", "Power", "PowerButton")
            names(217, "XF86Search", "Search")
            names(244, "XF86MonBrightnessAuto", "BrightnessAuto")
            // Mouse buttons (also "mouse:272" and so on) and the wheel.
            names(0x110, "MouseLeft", "LeftButton", "Button1")
            names(0x111, "MouseRight", "RightButton", "Button3")
            names(0x112, "MouseMiddle", "MiddleButton", "Button2")
            names(0x113, "MouseSide", "SideButton", "Button8")
            names(0x114, "MouseExtra", "ExtraButton", "Button9")
            names(0x115, "MouseForward", "ForwardButton")
            names(0x116, "MouseBack", "BackButton")
            names(WHEEL_UP, "WheelUp", "ScrollUp", "MouseUp")
            names(WHEEL_DOWN, "WheelDown", "ScrollDown", "MouseDown")
            names(WHEEL_LEFT, "WheelLeft", "ScrollLeft")
            names(WHEEL_RIGHT, "WheelRight", "ScrollRight")
            // A tablet pad's buttons, from 1 (as printed on tablets).
            for (n in 1..PAD_BUTTONS) names(padButton(n - 1), "Pad$n", "PadButton$n", "TabletPad$n")
        }

        /** How many pad buttons have names ("Pad1" to "Pad32"). */
        const val PAD_BUTTONS = 32

        /** The pretend key code of a tablet pad's button [index] (0 the first: "Pad1"), for [key]. */
        @JvmStatic
        fun padButton(index: Int): Int = 0x10100 + index

        /** Pretend key codes for one step of the wheel. */
        const val WHEEL_UP = 0x10001
        const val WHEEL_DOWN = 0x10002
        const val WHEEL_LEFT = 0x10003
        const val WHEEL_RIGHT = 0x10004

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
                // "mouse:276": a button by its Linux code.
                Regex("(?i)mouse:(\\d+)").matchEntire(part.trim())?.let { m ->
                    keys += part to m.groupValues[1].toInt().also { require(it in 0x110..0x2ff) { "\"$part\" is not a mouse button (mouse:272 to mouse:767)" } }
                    continue
                }
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

        /**
         * As [parse], but modifiers alone are allowed too: "Super" is a tap of Super (the key
         * is null then).
         */
        fun parseAllowingTap(combination: String): Pair<Set<Modifier>, Int?> {
            val all = parts(combination)
            if (all.isNotEmpty() && all.all { normalize(it) in MODIFIER_NAMES }) return all.map { MODIFIER_NAMES.getValue(normalize(it)) }.toSet() to null
            return parse(combination)
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
