package dev.caulis.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Num Lock switched on and off by the compositor: the locked modifiers say so, and keys still work. */
class NumLockTest {

    @Test
    fun `num lock on and off`() {
        Keymap.fromNames(layouts = "us", options = "").use { keymap ->
            keymap.newState().use { state ->
                assertTrue(state.setNumLock(true))
                assertEquals(16, state.modifiers.locked and 16)
                assertFalse(state.setNumLock(true), "on already: nothing changes")
                // The keypad's 1 types "1" now (KP_1 rather than KP_End).
                assertEquals("1", state.text(79))
                assertTrue(state.setNumLock(false))
                assertEquals(0, state.modifiers.locked and 16)
            }
        }
    }
}

/** Keyboard layouts: a switch on keys (Super+Space) and by the compositor, Num Lock kept. */
class KeyboardLayoutTest {

    @Test
    fun `super space switches the layout, and so does setLocks`() {
        Keymap.fromNames(layouts = "us,ru", options = "grp:win_space_toggle").use { keymap ->
            assertEquals(2, keymap.layoutCount)
            keymap.newState().use { state ->
                assertEquals(0, state.modifiers.group)
                state.update(125, true) // Super
                state.update(57, true) // Space
                state.update(57, false)
                state.update(125, false)
                assertEquals(1, state.modifiers.group)
                assertEquals("ф", state.text(30)) // A on the Russian layout
                state.setNumLock(true)
                assertTrue(state.setLocks(state.modifiers.locked, 0))
                assertEquals(0, state.modifiers.group)
                assertEquals(16, state.modifiers.locked and 16, "Num Lock stays on")
            }
        }
    }
}
