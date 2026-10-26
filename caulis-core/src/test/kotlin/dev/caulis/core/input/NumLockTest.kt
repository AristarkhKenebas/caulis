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
