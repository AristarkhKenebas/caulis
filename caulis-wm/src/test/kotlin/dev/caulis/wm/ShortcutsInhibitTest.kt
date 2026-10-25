package dev.caulis.wm

import dev.caulis.protocol.client.keyboardshortcutsinhibitunstablev1.ZwpKeyboardShortcutsInhibitManagerV1
import dev.caulis.protocol.client.keyboardshortcutsinhibitunstablev1.ZwpKeyboardShortcutsInhibitorV1
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** A window that keeps the compositor's keys (a compositor running in it), and the way back. */
class ShortcutsInhibitTest {

    private val harness = CaulisTest()

    @Test
    fun `a window keeps the keys while it has the keyboard, Super+Escape takes them back`() = with(harness) {
        val pressed = AtomicInteger()
        withCaulis({ bind("Super+B") { pressed.incrementAndGet() } }) { caulis, client ->
            val window = client.window().also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow != null } }
            var active = false
            val inhibitor = client.bind("zwp_keyboard_shortcuts_inhibit_manager_v1", ZwpKeyboardShortcutsInhibitManagerV1, 1)
                .inhibitShortcuts(window.surface, client.seat)
            inhibitor.events = object : ZwpKeyboardShortcutsInhibitorV1.Events {
                override fun active(proxy: ZwpKeyboardShortcutsInhibitorV1) { active = true }
                override fun inactive(proxy: ZwpKeyboardShortcutsInhibitorV1) { active = false }
            }
            client.waitFor { active }

            fun superAnd(code: Int) = caulis.onLoop { r ->
                r.key(0, KEY_SUPER, true)
                r.key(0, code, true)
                r.key(0, code, false)
                r.key(0, KEY_SUPER, false)
            }
            superAnd(KEY_B)
            assertEquals(0, pressed.get(), "Super+B went to the window")

            superAnd(KEY_ESC)
            client.waitFor { !active }
            superAnd(KEY_B)
            assertEquals(1, pressed.get(), "the compositor's again")
        }
    }

    private companion object {
        const val KEY_SUPER = 125
        const val KEY_B = 48
        const val KEY_ESC = 1
    }
}
