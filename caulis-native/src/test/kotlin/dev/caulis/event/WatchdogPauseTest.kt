package dev.caulis.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stuck loop's report says what the whole program went through meanwhile. */
class WatchdogPauseTest {

    @Test
    fun `major faults are read from proc stat, a name with spaces too`() {
        assertEquals(7L, Watchdog.Pause.majorFaults("1234 (my (odd) name) R 1 2 3 0 -1 4194304 128 0 7 0 5 6"))
        assertTrue((Watchdog.Pause.now().majorFaults ?: -1) >= 0)
    }

    @Test
    fun `the report tells collection, swapping and a paused program apart`() {
        val before = Watchdog.Pause(100, 10)
        assertTrue("mostly garbage collection" in Watchdog.Pause(1500, 10).since(before, 0))
        assertTrue("swapping" in Watchdog.Pause(100, 5000).since(before, 3000))
        assertTrue("not run" in Watchdog.Pause(100, 10).since(before, 3000))
        assertTrue("garbage collection 0 ms, 0 pages" in Watchdog.Pause(100, 10).since(before, 0))
    }
}
