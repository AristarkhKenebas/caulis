package dev.caulis.screenshot

import dev.caulis.core.input.Keymap
import dev.caulis.core.input.keySymbolName
import dev.caulis.log.Logger

/**
 * The names of the keys as shortcuts see them in a keyboard layout (Linux key code → "a",
 * "Return", "Escape"): the chooser's keys are looked up by them, so a letter is where your
 * layout has it. Made once per layout (xkb compiles it).
 */
internal object KeyNames {

    private var madeFor: List<String>? = null
    private var names: Map<Int, String> = emptyMap()

    /** The names in the layout [layouts] ("us,ru") with [variants], [options] and [model]. */
    fun of(layouts: String, variants: String, options: String, model: String, log: Logger): Map<Int, String> {
        val wanted = listOf(layouts, variants, options, model)
        if (wanted == madeFor) return names
        names = try {
            Keymap.fromNames(layouts, variants, options, model).use { keymap ->
                keymap.newState().use { state -> (1..KEY_CODES).associateWith { code -> keySymbolName(state.shortcutSymbol(code, keymap.layoutCount)) } }
            }
        } catch (e: Exception) {
            log.warn("The chooser's keys by their place on a US keyboard: the layout \"$layouts\" could not be read ($e)")
            US
        }
        madeFor = wanted
        log.trace { "The chooser's keys for the layout \"$layouts\"${variants.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}" }
        return names
    }

    private const val KEY_CODES = 255

    // Where the keys are on a US keyboard (if the layout cannot be read).
    private val US: Map<Int, String> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(16 + i, c.toString()) }
        "asdfghjkl".forEachIndexed { i, c -> put(30 + i, c.toString()) }
        "zxcvbnm".forEachIndexed { i, c -> put(44 + i, c.toString()) }
        put(1, "Escape"); put(15, "Tab"); put(28, "Return"); put(57, "space"); put(96, "KP_Enter")
    }
}
