package dev.caulis.wm

import dev.caulis.core.input.KeyBindings
import dev.caulis.tiling.Direction
import dev.caulis.tiling.TilingCommand
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All the usual keys of a [Caulis] compositor, each with a name: change one, or turn it
 * off with null. Set them in `keys { }`:
 *
 * ```
 * caulis {
 *     keys {
 *         terminal = "Super+T"         // another combination
 *         close = "Super+Shift+Q"
 *         dumpState = null             // off
 *         workspace = "Alt"            // Alt+1..9 instead of Super+1..9
 *         quit = "Ctrl+Alt+Delete"     // or null: no key quits
 *     }
 *     bind("Super+B") { launch("firefox") }  // keys of your own come on top
 * }
 * ```
 *
 * Some keys come as a group: [workspace] and [moveToWorkspace] are the modifiers in front
 * of 1..9, [move], [resize] and [preselect] the ones in front of the arrow keys, [consoles]
 * the ones in front of F1..F12. Your own [Caulis.bind] wins over a usual key on the same
 * combination. [off] turns all of them off but [quit] and [consoles].
 *
 * A combination is written as people say it: modifiers (Super, Ctrl, Alt, Shift) and a key
 * (a letter, a digit, F1..F12, Return, Tab, Escape, Space, BackSpace, Delete, the arrows
 * Left/Right/Up/Down, Grave, ...), e.g. "Super+Shift+Return". A wrong name is reported
 * right where it is set.
 */
class Keys internal constructor() {

    // Every key by name; first, as the settings below put themselves in here.
    private val values = LinkedHashMap<String, String?>()

    /** Open the terminal ([Caulis.terminal]). */
    var terminal by key("Super+Return")

    /** Close the focused window. */
    var close by key("Super+Q")

    /** Lock the screen ([Caulis.lockCommand]). */
    var lock by key("Super+L")

    /** Write the state (windows, focus, panels) to the log. */
    var dumpState by key("Super+F12")

    /** Maximize the focused window, or back. */
    var maximize by key("Super+Up")

    /** Fullscreen the focused window, or back. */
    var fullscreen by key("Super+F")

    /** Minimize the focused window ([nextWindow] brings it back). */
    var minimize by key("Super+Down")

    /** Take the focused window out of the layout (floating), or back. */
    var float by key("Super+V")

    /** Focus the next window (also brings minimized ones back). */
    var nextWindow by key("Alt+Tab")

    /** The overview: the whole workspace zoomed out; click a window to go there. */
    var overview by key("Super+Tab")

    /** Focus the window on the left / right (or the monitor beside). */
    var focusLeft by key("Super+Left")
    var focusRight by key("Super+Right")

    /** Modifiers + 1..9: go to that workspace. */
    var workspace by modifiers("Super")

    /** Modifiers + 1..9: move the focused window to that workspace. */
    var moveToWorkspace by modifiers("Super+Shift")

    /**
     * Modifiers + an arrow: move the window in the layout; past its edge (or when it
     * floats), to the monitor beside.
     */
    var move by modifiers("Super+Shift")

    /** Modifiers + an arrow: resize the window in layouts that remember sizes. */
    var resize by modifiers("Super+Ctrl")

    /**
     * Modifiers + an arrow, whatever fits the layout: where the next window goes (trees),
     * move the view (canvas), or take the window into the next column (columns).
     */
    var preselect by modifiers("Super+Alt")

    /** Tiling: rotate the split, even out the sizes, the next column width. */
    var rotate by key("Super+R")
    var balance by key("Super+B")
    var columnWidth by key("Super+W")

    /** Manual tiling: the next window opens beside / below the focused one. */
    var splitBeside by key("Super+S")
    var splitBelow by key("Super+Shift+S")

    /**
     * Quit the compositor (every program in it closes too). Works on a locked screen as
     * well, so a broken locker can never shut you out. Null: no key quits.
     */
    var quit by key("Ctrl+Alt+BackSpace")

    /**
     * Modifiers + F1..F12: switch to a text console (only on the screen, not nested).
     * Works on a locked screen as well. Null: off.
     */
    var consoles by modifiers("Ctrl+Alt")

    /** Hold these and drag a window: left button moves it, right button resizes it. Null: off. */
    var windowDrag by modifiers("Super")

    /** Turns every usual key off except [quit] and [consoles] (set those to null as well, if you mean it). */
    fun off() {
        for (name in names) if (name != "quit" && name != "consoles") values[name] = null
    }

    /** Every usual key by name (as above) and its combination, null when off. */
    fun all(): Map<String, String?> = LinkedHashMap(values)

    // --- how they are kept ------------------------------------------------------------------

    private val names get() = values.keys.toList()

    private fun key(default: String?) = Setting(default) { KeyBindings.parse(it) }
    private fun modifiers(default: String?) = Setting(default) { require(KeyBindings.modifiers(it).isNotEmpty()) { "\"$it\" has no modifier (Super, Ctrl, Alt, Shift)" } }

    private inner class Setting(private val default: String?, private val check: (String) -> Unit) : ReadWriteProperty<Keys, String?> {
        operator fun provideDelegate(thisRef: Keys, property: KProperty<*>): Setting {
            values[property.name] = default
            return this
        }

        override fun getValue(thisRef: Keys, property: KProperty<*>) = values[property.name]

        override fun setValue(thisRef: Keys, property: KProperty<*>, value: String?) {
            if (value != null) {
                try {
                    check(value)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("keys.${property.name}: ${e.message}")
                }
            }
            values[property.name] = value
        }
    }

    // The combinations the usual keys make now, with their names and what they do (not quit,
    // consoles and windowDrag: the compositor binds those itself).
    internal fun bindings(): List<Triple<String, String, Caulis.() -> Unit>> = buildList {
        fun add(name: String, combination: String?, action: Caulis.() -> Unit) {
            if (combination != null) add(Triple(name, combination, action))
        }
        add("terminal", terminal) { openTerminal() }
        add("close", close) { closeFocused() }
        add("dumpState", dumpState) { dumpState() }
        add("lock", lock) { lock() }
        add("maximize", maximize) { focusedWindow?.let { desktop.setMaximized(it, !it.maximized) } }
        add("fullscreen", fullscreen) { focusedWindow?.let { desktop.setFullscreen(it, !it.fullscreen) } }
        add("minimize", minimize) { focusedWindow?.let(desktop::minimize) }
        add("float", float) { focusedWindow?.let { desktop.setFloating(it, !desktop.isFloating(it)) } }
        add("nextWindow", nextWindow) { desktop.cycleFocus() }
        add("overview", overview) { desktop.toggleOverview() }
        add("focusLeft", focusLeft) { desktop.focusDirection(-1, 0) }
        add("focusRight", focusRight) { desktop.focusDirection(1, 0) }
        for (n in 1..9) {
            add("workspace", workspace?.let { "$it+$n" }) { desktop.switchTo(n) }
            add("moveToWorkspace", moveToWorkspace?.let { "$it+$n" }) { focusedWindow?.let { desktop.moveToWorkspace(it, n) } }
        }
        for (direction in Direction.entries) {
            val arrow = direction.name.lowercase().replaceFirstChar(Char::uppercase) // "Left", ...
            add("move", move?.let { "$it+$arrow" }) { desktop.tilingCommand(TilingCommand.Move(direction)) || moveToMonitorBeside(direction) }
            add("resize", resize?.let { "$it+$arrow" }) { desktop.tilingCommand(TilingCommand.Resize(direction)) }
            add("preselect", preselect?.let { "$it+$arrow" }) {
                desktop.tilingCommand(TilingCommand.Preselect(direction)) ||
                    desktop.tilingCommand(TilingCommand.Pan(direction)) ||
                    desktop.tilingCommand(TilingCommand.Consume(direction))
            }
        }
        add("rotate", rotate) { desktop.tilingCommand(TilingCommand.Rotate) }
        add("balance", balance) { desktop.tilingCommand(TilingCommand.Balance) }
        add("columnWidth", columnWidth) { desktop.tilingCommand(TilingCommand.CycleWidth) }
        add("splitBeside", splitBeside) { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = true)) }
        add("splitBelow", splitBelow) { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = false)) }
    }
}
