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
    fun newState(): KeyboardState = KeyboardState(Xkb.xkb_state_new(keymap))

    override fun close() {
        shared.close()
        Xkb.xkb_keymap_unref(keymap)
        Xkb.xkb_context_unref(context)
    }

    companion object {
        /**
         * Compiles a keymap from layout names, as in `setxkbmap`:
         * layouts "us,ru", options "grp:win_space_toggle" (Super+Space switches layout).
         */
        fun fromNames(
            layouts: String = "us,ru",
            variants: String = "",
            options: String = "grp:win_space_toggle",
            model: String = "pc105",
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
class KeyboardState internal constructor(private val state: MemorySegment) : AutoCloseable {

    /** Modifier bits as clients need them (see [Modifiers]). */
    var modifiers = Modifiers(0, 0, 0, 0)
        private set

    /** Updates the state for a key press or release. Returns true if the modifiers changed. */
    fun update(evdevCode: Int, pressed: Boolean): Boolean {
        val direction = if (pressed) Xkb.XKB_KEY_DOWN() else Xkb.XKB_KEY_UP()
        Xkb.xkb_state_update_key(state, evdevCode + 8, direction)
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

    /** The symbol a key produces now, e.g. XKB_KEY_q (for compositor shortcuts). */
    fun symbol(evdevCode: Int): Int = Xkb.xkb_state_key_get_one_sym(state, evdevCode + 8)

    /** The character a key types now (with the layout and Shift, Caps Lock, AltGr), or null for none (arrows, Ctrl, F1). */
    fun text(evdevCode: Int): String? {
        val code = Xkb.xkb_state_key_get_utf32(state, evdevCode + 8)
        if (code < 0x20 || code == 0x7f || !Character.isValidCodePoint(code)) return null
        return String(Character.toChars(code))
    }

    override fun close() = Xkb.xkb_state_unref(state)

    /** Modifier state in the form of wl_keyboard.modifiers. */
    data class Modifiers(val depressed: Int, val latched: Int, val locked: Int, val group: Int)
}

/** Name of a key symbol, e.g. "Return" (for logs and shortcut parsing). */
fun keySymbolName(symbol: Int): String = Arena.ofConfined().use { arena ->
    val buffer = arena.allocate(64)
    val length = Xkb.xkb_keysym_get_name(symbol, buffer, 64)
    if (length <= 0) "unknown" else buffer.getString(0)
}

