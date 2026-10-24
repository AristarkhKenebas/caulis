package dev.iriswm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortcutsTest {

    private val events = ArrayList<String>()
    private val shortcuts = Shortcuts(
        quit = { events += "quit" },
        openTerminal = { events += "terminal" },
        switchConsole = { events += "console $it" },
    )

    @Test
    fun `ctrl alt backspace quits and is not passed on`() {
        assertFalse(shortcuts.key(29, true)) // Ctrl goes to programs as usual
        assertFalse(shortcuts.key(56, true)) // Alt too
        assertTrue(shortcuts.key(14, true))
        assertTrue(shortcuts.key(14, false)) // its release is swallowed as well
        assertFalse(shortcuts.key(56, false))
        assertEquals(listOf("quit"), events)
    }

    @Test
    fun `ctrl alt f3 switches to console 3`() {
        shortcuts.key(29, true)
        shortcuts.key(56, true)
        assertTrue(shortcuts.key(61, true))
        assertEquals(listOf("console 3"), events)
    }

    @Test
    fun `plain keys are passed on`() {
        assertFalse(shortcuts.key(30, true))
        assertFalse(shortcuts.key(30, false))
        assertTrue(events.isEmpty())
    }
}
