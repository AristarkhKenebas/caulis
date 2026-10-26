package dev.caulis.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** onKey and onPointerMotion see input first and can take it. */
class RawInputTest {

    private val harness = CaulisTest()

    @Test
    fun `a key taken by onKey reaches no binding, a movement taken leaves the pointer`() = with(harness) {
        val seen = ArrayList<KeyInput>()
        var bound = 0
        withCaulis({
            bind("Super+h") { bound++ }
            onKey { key ->
                seen += key
                key.keysym == "h" && key.modifiers and KeyInput.Modifiers.LOGO != 0
            }
            onPointerMotion { dx, _ -> dx > 50 }
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                r.key(0, KEY_SUPER, true)
                r.key(0, KEY_H, true)
                r.key(0, KEY_H, false)
                r.key(0, KEY_SUPER, false)
            }
            assertEquals(0, bound, "taken: Super+h never fired")
            assertTrue(seen.any { it.keysym == "Super_L" && it.isModifier && it.pressed && it.modifiers == KeyInput.Modifiers.LOGO && it.modifiersBefore == 0 })
            val before = caulis.onLoop { it.desktop.pointerX }
            caulis.onLoop { r -> r.pointerMovedBy(0, 100.0, 0.0) }
            assertEquals(before, caulis.onLoop { it.desktop.pointerX }, "taken: the pointer did not move")
            caulis.onLoop { r -> r.pointerMovedBy(0, 10.0, 0.0) }
            assertEquals(before + 10, caulis.onLoop { it.desktop.pointerX }, 0.01)
        }
    }

    @Test
    fun `a key a program saw go down is let go for it even when a binding takes the release`() = with(harness) {
        withCaulis({
            // As newm's Super+1: taken while Super is held, the press as well as the release.
            onKey { key -> key.keysym == "1" && key.modifiers and KeyInput.Modifiers.LOGO != 0 }
        }, headless = true) { caulis, client ->
            client.window { it.setAppId("terminal") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "terminal" }
            val keys = java.util.Collections.synchronizedList(ArrayList<String>())
            client.seat.getKeyboard().events = object : dev.caulis.protocol.client.wayland.WlKeyboard.Events {
                override fun key(proxy: dev.caulis.protocol.client.wayland.WlKeyboard, serial: Int, time: Int, key: Int, state: Int) {
                    keys += "$key ${if (state == 1) "down" else "up"}"
                }
            }
            var looks = 0
            client.waitFor { ++looks > 3 }
            // 1 down (the terminal has it), Super down, 1 up (Super+1 takes it), Super up.
            caulis.onLoop { r ->
                r.key(0, KEY_1, true)
                r.key(0, KEY_SUPER, true)
                r.key(0, KEY_1, false)
                r.key(0, KEY_SUPER, false)
            }
            client.waitFor { keys.contains("$KEY_1 up") }
            assertTrue("$KEY_1 down" in keys, "the terminal saw 1 go down")
        }
    }

    private companion object {
        const val KEY_1 = 2
        const val KEY_SUPER = 125
        const val KEY_H = 35
    }
}
