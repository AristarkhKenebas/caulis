package dev.caulis.wm

import dev.caulis.core.input.KeyBindings.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Key combinations keep working (bindings broke "after a while"): a release an onKey handler
 * took, a layout switch or a new keymap while a modifier is held, Alt with Shift under
 * "grp:alt_shift_toggle", a key let go while a chooser (overlay) had the keys, a lost keyboard.
 */
class StuckKeysTest {

    private val harness = CaulisTest()

    private fun Running.tap(vararg codes: Int) {
        for (code in codes) key(0, code, true)
        for (code in codes.reversed()) key(0, code, false)
    }

    @Test
    fun `a release taken by onKey does not leave the modifier held for the combinations`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            bind("Ctrl+X") { fired += "ctrl+x" }
            // As a configuration that takes Super's release (a tap of Super opens something).
            onKey { key -> key.keysym == "Super_L" && !key.pressed }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                r.key(0, SUPER, true)
                r.key(0, SUPER, false)
                assertFalse(r.keys.isHeld(Modifier.SUPER), "Super is not held any more")
                r.tap(CTRL, X)
            }
            assertEquals(listOf("ctrl+x"), fired.toList())
        }
    }

    @Test
    fun `a repeating combination stops when onKey takes its release`() = with(harness) {
        var runs = 0
        withCaulis({
            bind("Super+Z", BindOptions(repeat = true)) { runs++ }
            onKey { key -> key.keysym == "z" && !key.pressed }
        }, headless = true) { caulis, client ->
            caulis.onLoop { r -> r.key(0, SUPER, true); r.key(0, Z, true); r.key(0, Z, false); r.key(0, SUPER, false) }
            val after = caulis.onLoop { runs }
            var looks = 0
            client.waitFor { Thread.sleep(50); ++looks > 16 } // past the repeat delay
            assertEquals(after, caulis.onLoop { runs }, "no repeats after the key was let go")
        }
    }

    @Test
    fun `a layout switch and a new keymap while Super is held leave it working and not stuck`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "us,ru"
            keyboardOptions = "grp:win_space_toggle"
            bindingModifiers = BindingModifiers.XKB
            bind("Super+D") { fired += "super+d" }
            bind("Ctrl+X") { fired += "ctrl+x" }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                r.key(0, SUPER, true)
                caulis.switchKeyboardLayout(1) // Russian: the key of D types "в"
                r.tap(D)
                caulis.keyboardOptions = "grp:alt_shift_toggle" // a reload gives a new keymap
                r.tap(D)
                r.key(0, SUPER, false)
                assertFalse(r.keys.isHeld(Modifier.SUPER), "Super is not stuck")
                assertEquals(0, r.seat.modifierBits and 64, "programs see Super let go")
                r.tap(CTRL, X)
            }
            assertEquals(listOf("super+d", "super+d", "ctrl+x"), fired.toList())
        }
    }

    @Test
    fun `Alt with Shift is still Alt and Shift under grp alt_shift_toggle`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "us,ru"
            keyboardOptions = "grp:alt_shift_toggle"
            bindingModifiers = BindingModifiers.XKB
            bind("Alt+Shift+K") { fired += "alt+shift+k" }
            bind("Shift+K") { fired += "shift+k" }
        }, headless = true) { caulis, _ ->
            // Either first: with Shift held, the key of Alt gives ISO_Next_Group.
            caulis.onLoop { r -> r.tap(ALT, SHIFT, K); r.tap(SHIFT, ALT, K) }
            assertEquals(listOf("alt+shift+k", "alt+shift+k"), fired.toList())
            caulis.onLoop { r -> assertEquals(emptySet(), r.keys.heldModifiers()) }
        }
    }

    @Test
    fun `onKey hears the release of a key let go while a chooser had the keys, and after a lost keyboard`() = with(harness) {
        val seen = CopyOnWriteArrayList<String>()
        withCaulis({
            onKey { key -> seen += "${key.keysym} ${if (key.pressed) "down" else "up"}"; false }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                r.key(0, SUPER, true)
                r.desktop.openOverlay(object : dev.caulis.core.Overlay {
                    override fun draw(monitor: dev.caulis.core.Monitor, canvas: dev.caulis.core.OverlayCanvas) {}
                })
                r.key(0, SUPER, false)
                r.desktop.closeOverlay()
            }
            assertEquals(listOf("Super_L down", "Super_L up"), seen.toList())
            seen.clear()
            caulis.onLoop { r ->
                r.key(0, CTRL, true)
                r.releaseKeys() // the keyboard went to another console
                r.key(0, X, true)
            }
            assertEquals(listOf("Control_L down", "Control_L up", "x down"), seen.toList())
            caulis.onLoop { r -> assertEquals(emptySet(), r.keys.heldModifiers()) }
        }
    }

    companion object {
        const val SUPER = 125
        const val CTRL = 29
        const val ALT = 56
        const val SHIFT = 42
        const val X = 45
        const val Z = 44
        const val D = 32
        const val K = 37
    }
}
