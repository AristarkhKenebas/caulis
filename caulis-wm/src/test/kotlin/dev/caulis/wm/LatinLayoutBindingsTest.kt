package dev.caulis.wm

import dev.caulis.protocol.client.wayland.WlKeyboard
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bindings work on every layout by the key they have in the first Latin layout (us,ru: Super+D
 * is the key of "в"), letters follow a Latin layout (German Z), the digits stay put (AZERTY),
 * and programs get what other compositors send: the key's code, the modifiers and the layout.
 */
class LatinLayoutBindingsTest {

    private val harness = CaulisTest()

    private fun Running.tap(vararg codes: Int) {
        for (code in codes) key(0, code, true)
        for (code in codes.reversed()) key(0, code, false)
    }

    // The usual three combinations, each pressed once on every layout of [layouts].
    private fun firesOnEveryLayout(layouts: String) = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = layouts
            keyboardOptions = "grp:win_space_toggle"
            bind("Super+D") { fired += "super+d" }
            bind("Super+Shift+M") { fired += "super+shift+m" }
            bind("Ctrl+Alt+T") { fired += "ctrl+alt+t" }
        }, headless = true) { caulis, _ ->
            val count = layouts.split(',').size
            for (layout in 0 until count) {
                caulis.onLoop { r ->
                    caulis.switchKeyboardLayout(layout)
                    assertEquals(layout, r.seat.layoutIndex)
                    r.tap(SUPER, D)
                    r.tap(SUPER, SHIFT, M)
                    r.tap(CTRL, ALT, T)
                }
            }
            val each = listOf("super+d", "super+shift+m", "ctrl+alt+t")
            assertEquals((0 until count).flatMap { each }, fired.toList(), "on $layouts")
        }
    }

    @Test
    fun `us ru - the bindings fire on both layouts`() = firesOnEveryLayout("us,ru")

    @Test
    fun `us ua gr - the bindings fire on all three layouts`() = firesOnEveryLayout("us,ua,gr")

    @Test
    fun `a layout switch with Super held, then Super+D on the Russian layout, nothing stuck`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "us,ru"
            keyboardOptions = "grp:win_space_toggle"
            bind("Super+D") { fired += "super+d" }
            bind("Ctrl+X") { fired += "ctrl+x" }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                r.key(0, SUPER, true)
                r.tap(SPACE) // Super+Space: Russian
                assertEquals(1, r.seat.layoutIndex)
                r.key(0, D, true)
                r.tap(SPACE) // back to us while D is held
                r.key(0, D, false)
                r.key(0, SUPER, false)
                assertEquals(emptySet(), r.keys.heldModifiers())
                r.tap(CTRL, X)
            }
            assertEquals(listOf("super+d", "ctrl+x"), fired.toList())
        }
    }

    @Test
    fun `letters follow a Latin layout, the digits stay where they are`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "de,fr"
            bind("Super+Z") { fired += "super+z" }
            bind("Super+A") { fired += "super+a" }
            bind("Super+4") { fired += "super+4" }
        }, headless = true) { caulis, _ ->
            // German: Z is where a US keyboard has Y (code 21).
            caulis.onLoop { r -> r.tap(SUPER, Y) }
            // French (AZERTY): A is where Q is; the key of 4 types "'" and is still Super+4.
            caulis.onLoop { r ->
                caulis.switchKeyboardLayout(1)
                r.tap(SUPER, Q)
                r.tap(SUPER, FOUR)
            }
            assertEquals(listOf("super+z", "super+a", "super+4"), fired.toList())
        }
    }

    @Test
    fun `bindingsUseFirstLatinLayout false - physical keys`() = with(harness) {
        val fired = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "de"
            bindingsUseFirstLatinLayout = false
            bind("Super+Z") { fired += "super+z" }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r -> r.tap(SUPER, Y); r.tap(SUPER, Z) }
            assertEquals(listOf("super+z"), fired.toList(), "only the key where a US keyboard has Z")
        }
    }

    @Test
    fun `onKey hears the Latin symbol and the one of the layout in use`() = with(harness) {
        val seen = CopyOnWriteArrayList<String>()
        withCaulis({
            keyboardLayouts = "us,ru"
            onKey { key -> if (key.pressed && key.evdevCode == D) seen += "${key.keysym} ${key.layoutKeysym}"; false }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r -> r.tap(D); caulis.switchKeyboardLayout(1); r.tap(D) }
            assertEquals(listOf("d d", "d Cyrillic_ve"), seen.toList())
        }
    }

    @Test
    fun `Ctrl+V on the Russian layout reaches a program as the key of V, Ctrl and layout 1`() = with(harness) {
        withCaulis({
            keyboardLayouts = "us,ru"
            keyboardOptions = "grp:win_space_toggle"
        }, headless = true) { caulis, client ->
            val events = Collections.synchronizedList(ArrayList<String>())
            var keymap = ""
            client.seat.getKeyboard().events = object : WlKeyboard.Events {
                override fun keymap(proxy: WlKeyboard, format: Int, fd: dev.caulis.ffi.Fd, size: Int) {
                    fd.use { keymap = String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/fd/${it.value}"))) }
                }
                override fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) {
                    events += "key $key ${if (state == 1) "down" else "up"}"
                }
                override fun modifiers(proxy: WlKeyboard, serial: Int, modsDepressed: Int, modsLatched: Int, modsLocked: Int, group: Int) {
                    events += "mods $modsDepressed group $group"
                }
            }
            client.window { it.setAppId("terminal") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "terminal" && keymap.isNotEmpty() }
            assertTrue("name[1]=\"English (US)\"" in keymap && "name[2]=\"Russian\"" in keymap, "the keymap programs get has both layouts")
            // Super+Space to Russian (Super and Space are the compositor's: the program hears only the layout).
            caulis.onLoop { r -> r.tap(SUPER, SPACE) }
            client.waitFor { events.any { it == "mods 0 group 1" } }
            events.clear()
            caulis.onLoop { r -> r.tap(CTRL, V) }
            client.waitFor { "key $V up" in events && events.last().startsWith("mods 0") }
            assertEquals(
                listOf("key $CTRL down", "mods 4 group 1", "key $V down", "key $V up", "key $CTRL up", "mods 0 group 1"),
                events.toList(),
            )
        }
    }

    private companion object {
        const val SUPER = 125
        const val SHIFT = 42
        const val CTRL = 29
        const val ALT = 56
        const val SPACE = 57
        const val D = 32
        const val M = 50
        const val T = 20
        const val V = 47
        const val X = 45
        const val Y = 21
        const val Z = 44
        const val Q = 16
        const val FOUR = 5
    }
}
