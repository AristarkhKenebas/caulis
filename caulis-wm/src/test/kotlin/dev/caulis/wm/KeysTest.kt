package dev.caulis.wm

import dev.caulis.core.input.KeyBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The usual keys: each one changed, turned off, or left alone. */
class KeysTest {

    private val harness = CaulisTest()

    @Test
    fun `a key moved to another combination, one turned off, and quit turned off`() = with(harness) {
        withCaulis({
            keys {
                close = "Super+X"
                workspace = "Alt"
                fullscreen = null
                quit = null
            }
        }) { caulis, client ->
            val window = client.window().also { it.draw() }
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }

            press(caulis, SUPER, F) // off: nothing
            assertEquals(false, caulis.onLoop { it.desktop.windows.single().fullscreen })
            press(caulis, CTRL, ALT, BACKSPACE) // off: still running
            press(caulis, ALT, KEY_3)
            assertEquals(3, caulis.onLoop { it.desktop.activeWorkspace.number }, "Alt+3")
            press(caulis, ALT, KEY_1)
            press(caulis, SUPER, Q) // no longer close
            client.connection.roundtrip()
            assertEquals(false, window.closeRequested)
            press(caulis, SUPER, X)
            client.waitFor { window.closeRequested }
        }
    }

    @Test
    fun `quit on another combination, and bind winning over a usual key`() = with(harness) {
        var mine = 0
        withCaulis({
            keys.quit = "Ctrl+Alt+Delete"
            bind("super", "q") { mine++ } // in parts, in small letters
        }) { caulis, client ->
            val window = client.window().also { it.draw() }
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }
            press(caulis, SUPER, Q)
            client.connection.roundtrip()
            assertEquals(1, mine)
            assertEquals(false, window.closeRequested, "not closed")
            caulis.onLoop { r -> listOf(CTRL, ALT, DELETE).forEach { r.key(0, it, true) } }
            client.waitFor { caulis.running == null }
        }
    }

    @Test
    fun `a key used twice is shown on screen, a mistake in user code too`() = with(harness) {
        withCaulis({
            keys { close = "super f" } // as fullscreen
            bind("Super+X") { error("a mistake") }
        }) { caulis, client ->
            client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "keys.close and keys.fullscreen are both super f: keys.close wins" in it.text } } }
            assertEquals(dev.caulis.core.NoticeLevel.WARNING, caulis.onLoop { r -> r.desktop.notices.first().level })
            press(caulis, SUPER, X)
            client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { it.level == dev.caulis.core.NoticeLevel.ERROR && "a mistake" in it.text } } }
        }
    }

    @Test
    fun `off leaves only quit and consoles, wrong names are said at once`() {
        val keys = Keys()
        keys.off()
        assertTrue(keys.all().filterKeys { it != "quit" && it != "consoles" }.values.all { it == null })
        assertEquals("Ctrl+Alt+BackSpace", keys.quit)
        assertTrue(keys.bindings().isEmpty())
        val wrong = assertFailsWith<IllegalArgumentException> { keys.close = "Super+Qq" }
        assertEquals("keys.close: Unknown key \"Qq\" in \"Super+Qq\"", wrong.message)
        assertFailsWith<IllegalArgumentException> { keys.workspace = "Q" }
        assertNull(keys.close)
        keys.close = keys.combo("super", "shift", "q")
        keys.workspace = "wIn ALT"
        val made = keys.bindings().map { it.second }
        assertEquals("super+shift+q", made.first())
        assertEquals(setOf(KeyBindings.Modifier.SUPER, KeyBindings.Modifier.ALT) to 4, KeyBindings.parse(made[3]), "wIn ALT+3")
    }

    private fun CaulisTest.press(caulis: Caulis, vararg codes: Int) {
        caulis.onLoop { r ->
            codes.forEach { r.key(0, it, true) }
            codes.reversed().forEach { r.key(0, it, false) }
        }
    }

    private companion object {
        const val SUPER = 125
        const val CTRL = 29
        const val ALT = 56
        const val Q = 16
        const val X = 45
        const val F = 33
        const val KEY_1 = 2
        const val KEY_3 = 4
        const val BACKSPACE = 14
        const val DELETE = 111
    }
}
