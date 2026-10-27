package dev.caulis.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A copy of a configuration: the same settings, and changing it leaves the first as it was. */
class SnapshotTest {

    @Test
    fun `a copy has the settings, and its lists are its own`() {
        val first = Caulis().apply {
            terminal = "kitty"
            x11Programs += "steam"
            keys { quitWhileLocked = true; close = "Super+W" }
            systemKeys { volumeStep = 3 }
            bind("Super+B") { }
        }
        val copy = first.snapshot()
        assertEquals("kitty", copy.terminal)
        assertEquals(setOf("steam"), copy.x11Programs)
        assertTrue(copy.keys.quitWhileLocked)
        assertEquals("Super+W", copy.keys.all()["close"])
        assertEquals(3, copy.systemKeys.volumeStep)
        assertEquals(1, copy.bindings.size)

        copy.x11Programs += "wine"
        copy.systemKeys.volumeStep = 7
        copy.bind("Super+C") { }
        assertEquals(setOf("steam"), first.x11Programs)
        assertEquals(3, first.systemKeys.volumeStep)
        assertEquals(1, first.bindings.size)
    }
}
