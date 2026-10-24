package dev.caulis.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyBindingsTest {

    private val events = ArrayList<String>()
    private val keys = KeyBindings().apply {
        bind("Ctrl+Alt+BackSpace") { events += "quit" }
        bind("Super+Return") { events += "terminal" }
        bind("Ctrl+Alt+F3") { events += "console 3" }
        bind("Super+Shift+Q") { events += "force quit" }
    }

    @Test
    fun `a combination runs and is not passed on, its parts are`() {
        assertFalse(keys.key(29, true)) // Ctrl goes to programs as usual
        assertFalse(keys.key(56, true)) // Alt too
        assertTrue(keys.key(14, true))
        assertTrue(keys.key(14, false)) // its release is swallowed as well
        assertFalse(keys.key(56, false))
        assertEquals(listOf("quit"), events)
    }

    @Test
    fun `right-hand modifiers count too`() {
        keys.key(97, true) // right Ctrl
        keys.key(100, true) // right Alt
        assertTrue(keys.key(61, true)) // F3
        assertEquals(listOf("console 3"), events)
    }

    @Test
    fun `modifiers must match exactly`() {
        keys.key(125, true) // Super
        keys.key(42, true) // Shift
        assertFalse(keys.key(28, true), "Super+Shift+Return is not Super+Return")
        assertTrue(keys.key(16, true)) // Q
        assertEquals(listOf("force quit"), events)
    }

    @Test
    fun `plain keys are passed on`() {
        assertFalse(keys.key(30, true))
        assertFalse(keys.key(30, false))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `typos give readable errors`() {
        assertFailsWith<IllegalArgumentException> { KeyBindings().bind("Hyper+Q") {} }
        assertFailsWith<IllegalArgumentException> { KeyBindings().bind("Super+Banana") {} }
    }
}
