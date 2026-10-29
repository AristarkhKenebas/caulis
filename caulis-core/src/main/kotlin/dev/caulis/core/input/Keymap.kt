package dev.caulis.core.input

import dev.caulis.ffi.CStrings
import dev.caulis.ffi.SharedMemory
import dev.caulis.ffi.c.xkb.Xkb
import dev.caulis.ffi.c.xkb.xkb_rule_names
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A keyboard layout set (for example "us,ru"), compiled by xkbcommon.
 *
 * Clients get the same keymap as a file ([shared]) so they turn key codes into the same
 * letters as the compositor does.
 */
class Keymap private constructor(
    private val context: MemorySegment,
    internal val keymap: MemorySegment,
) : AutoCloseable {

    /** The keymap as text, in a sealed shared memory file that can be sent to clients. */
    val shared: SharedMemory

    /** Size in bytes clients must be told (the text plus its terminating zero). */
    val sharedSize: Int

    init {
        val text = CStrings.takeOwned(Xkb.xkb_keymap_get_as_string(keymap, Xkb.XKB_KEYMAP_FORMAT_TEXT_V1()))
            ?: error("xkbcommon could not write the keymap")
        val bytes = text.toByteArray() + 0
        sharedSize = bytes.size
        // Sealed against writing: one client must not change the keymap the others get.
        shared = SharedMemory.sealed("caulis-keymap", bytes)
    }

    /** Tracks pressed keys and modifiers for this keymap. */
    fun newState(): KeyboardState = KeyboardState(Xkb.xkb_state_new(keymap), Xkb.xkb_state_new(keymap))

    /** How many layouts it has ("us,ru": two). */
    val layoutCount: Int get() = Xkb.xkb_keymap_num_layouts(keymap)

    /** False once closed (a [Seat] closes the keymap it replaces, and its own when it is closed). */
    var isOpen = true
        private set

    /** Lets go of the keymap; closing it again does nothing. */
    override fun close() {
        if (!isOpen) return
        isOpen = false
        shared.close()
        Xkb.xkb_keymap_unref(keymap)
        Xkb.xkb_context_unref(context)
    }

    companion object {
        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        /**
         * Compiles a keymap from layout names, as in `setxkbmap`: e.g. layouts "us,ru",
         * options "grp:win_space_toggle" (Super+Space switches layout). By default what the
         * environment says (XKB_DEFAULT_LAYOUT, _VARIANT, _OPTIONS, _MODEL), else "us".
         */
        @JvmOverloads
        @JvmStatic
        fun fromNames(
            layouts: String = env("XKB_DEFAULT_LAYOUT") ?: "us",
            variants: String = env("XKB_DEFAULT_VARIANT") ?: "",
            options: String = env("XKB_DEFAULT_OPTIONS") ?: "",
            model: String = env("XKB_DEFAULT_MODEL") ?: "pc105",
        ): Keymap {
            val context = Xkb.xkb_context_new(Xkb.XKB_CONTEXT_NO_FLAGS())
            check(context != MemorySegment.NULL) { "xkb_context_new failed" }
            val keymap = Arena.ofConfined().use { arena ->
                val names = xkb_rule_names.allocate(arena)
                xkb_rule_names.rules(names, arena.allocateFrom("evdev"))
                xkb_rule_names.model(names, arena.allocateFrom(model))
                xkb_rule_names.layout(names, arena.allocateFrom(layouts))
                xkb_rule_names.variant(names, arena.allocateFrom(variants))
                xkb_rule_names.options(names, arena.allocateFrom(options))
                Xkb.xkb_keymap_new_from_names(context, names, Xkb.XKB_KEYMAP_COMPILE_NO_FLAGS())
            }
            if (keymap == MemorySegment.NULL) {
                Xkb.xkb_context_unref(context)
                error("Unknown keyboard layout: $layouts ($variants) options $options")
            }
            return Keymap(context, keymap)
        }
    }
}

/**
 * Which keys and modifiers are pressed right now.
 *
 * Key codes here are Linux input codes (evdev), as libinput and Wayland use them;
 * xkbcommon wants them plus 8, which this class adds.
 */
class KeyboardState internal constructor(
    private val state: MemorySegment,
    // A second state to ask what a key gives in another layout (see shortcutSymbol).
    private val probe: MemorySegment,
) : AutoCloseable {

    /** Modifier bits as clients need them (see [Modifiers]). */
    var modifiers = Modifiers(0, 0, 0, 0)
        private set

    /** Updates the state for a key press or release. Returns true if the modifiers changed. */
    fun update(evdevCode: Int, pressed: Boolean): Boolean {
        val direction = if (pressed) Xkb.XKB_KEY_DOWN() else Xkb.XKB_KEY_UP()
        Xkb.xkb_state_update_key(state, evdevCode + 8, direction)
        return readModifiers()
    }

    /** Turns Num Lock on or off (Mod2, where keymaps put it). Returns true if the modifiers changed. */
    fun setNumLock(on: Boolean): Boolean {
        val m = modifiers
        val locked = if (on) m.locked or NUM_LOCK else m.locked and NUM_LOCK.inv()
        Xkb.xkb_state_update_mask(state, m.depressed, m.latched, locked, 0, 0, m.group)
        return readModifiers()
    }

    /** Sets the locked modifiers (Num Lock, Caps Lock) and the layout in use ([layout]). Returns true if anything changed. */
    fun setLocks(locked: Int, layout: Int): Boolean {
        val m = modifiers
        Xkb.xkb_state_update_mask(state, m.depressed, m.latched, locked, 0, 0, layout)
        return readModifiers()
    }

    // The modifiers from xkbcommon's state; true if they changed.
    private fun readModifiers(): Boolean {
        val now = Modifiers(
            depressed = Xkb.xkb_state_serialize_mods(state, Xkb.XKB_STATE_MODS_DEPRESSED()),
            latched = Xkb.xkb_state_serialize_mods(state, Xkb.XKB_STATE_MODS_LATCHED()),
            locked = Xkb.xkb_state_serialize_mods(state, Xkb.XKB_STATE_MODS_LOCKED()),
            group = Xkb.xkb_state_serialize_layout(state, Xkb.XKB_STATE_LAYOUT_EFFECTIVE()),
        )
        val changed = now != modifiers
        modifiers = now
        return changed
    }

    private companion object {
        const val NUM_LOCK = 1 shl 4 // Mod2
    }

    /** The symbol a key produces now, e.g. XKB_KEY_q (for compositor shortcuts). */
    fun symbol(evdevCode: Int): Int = Xkb.xkb_state_key_get_one_sym(state, evdevCode + 8)

    /**
     * The symbol a key gives on its own in the layout in use, whatever is held: Alt is Alt_L
     * even while Shift is held (with "grp:alt_shift_toggle" Shift+Alt gives ISO_Next_Group),
     * Caps Lock is Super_L with "caps:super". For telling which keys are modifiers.
     */
    fun baseSymbol(evdevCode: Int): Int {
        Xkb.xkb_state_update_mask(probe, 0, 0, 0, 0, 0, modifiers.group)
        return Xkb.xkb_state_key_get_one_sym(probe, evdevCode + 8)
    }

    /** The character a key types now (with the layout and Shift, Caps Lock, AltGr), or null for none (arrows, Ctrl, F1). */
    fun text(evdevCode: Int): String? {
        val code = Xkb.xkb_state_key_get_utf32(state, evdevCode + 8)
        if (code < 0x20 || code == 0x7f || !Character.isValidCodePoint(code)) return null
        return String(Character.toChars(code))
    }

    /**
     * The character [evdevCode] types in layout number [layout] with Shift held or not, whatever
     * is held now — for an on-screen keyboard's labels — or null for none.
     */
    fun textIn(evdevCode: Int, layout: Int, shift: Boolean): String? {
        Xkb.xkb_state_update_mask(probe, if (shift) 1 else 0, 0, 0, 0, 0, layout)
        val code = Xkb.xkb_state_key_get_utf32(probe, evdevCode + 8)
        if (code < 0x20 || code == 0x7f || !Character.isValidCodePoint(code)) return null
        return String(Character.toChars(code))
    }

    /**
     * The symbol a key means for shortcuts: a Latin letter of the layout in use as it is; else for
     * a printing key what it gives in the first layout where it is Latin — so Super+D is Super+D
     * on the Russian layout too (where the key types "в"), and Super+/ is Super+/ (where it types
     * "."). Keys like F1, Return and the arrows are as they are.
     */
    fun shortcutSymbol(evdevCode: Int, layoutCount: Int): Int {
        val now = symbol(evdevCode)
        // Keys that are the same in every layout (Return, F1, the arrows, XF86...) and a single layout: as they are.
        // (Not a Unicode symbol, 0x01000100 on: Armenian, Georgian letters are looked up as the others.)
        val unicode = now in 0x01000100..0x0110ffff
        if (layoutCount <= 1 || (now >= 0xfe00 && !unicode) || now in 0..0x1f) return now
        // A Latin letter in the layout in use (French, German): that one — on AZERTY the key of "a" is Super+A.
        if (now in 0x41..0x5a || now in 0x61..0x7a || now in 0xc0..0xff && now != 0xd7 && now != 0xf7) return now
        // A printing key: what it gives in the first layout where it is Latin (on "us,ru" the us
        // one, also for punctuation: the key of "." on the Russian layout is "/" for shortcuts).
        val m = modifiers
        for (layout in 0 until layoutCount) {
            Xkb.xkb_state_update_mask(probe, m.depressed, m.latched, m.locked, 0, 0, layout)
            val sym = Xkb.xkb_state_key_get_one_sym(probe, evdevCode + 8)
            if (sym in 0x20..0xff) return sym
        }
        return now
    }

    override fun close() {
        Xkb.xkb_state_unref(state)
        Xkb.xkb_state_unref(probe)
    }

    /** Modifier state in the form of wl_keyboard.modifiers. */
    data class Modifiers(val depressed: Int, val latched: Int, val locked: Int, val group: Int)
}

/** Name of a key symbol, e.g. "Return" (for logs and shortcut parsing). */
fun keySymbolName(symbol: Int): String = Arena.ofConfined().use { arena ->
    val buffer = arena.allocate(64)
    val length = Xkb.xkb_keysym_get_name(symbol, buffer, 64)
    if (length <= 0) "unknown" else buffer.getString(0)
}

