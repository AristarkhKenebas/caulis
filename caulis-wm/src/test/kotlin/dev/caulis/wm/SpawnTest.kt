package dev.caulis.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Programs started with spawn are inside the compositor; the first monitors' events can use the desktop. */
class SpawnTest {

    private val harness = CaulisTest()

    @Test
    fun `onMonitorAdded sees the desktop, spawn gives a process inside the compositor`() = with(harness) {
        val monitors = ArrayList<Int>()
        withCaulis({
            onMonitorAdded { monitors += desktop.monitors.size }
        }, headless = true) { caulis, client ->
            assertEquals(listOf(1), monitors, "the first monitor's event, with the desktop there")
            val socket = caulis.onLoop { it.socket }
            // (It checks its WAYLAND_DISPLAY itself: 0 if it is the compositor's.)
            val process = caulis.onLoop { it.config.spawn(listOf("/bin/sh", "-c", "test \"\$WAYLAND_DISPLAY\" = \"$socket\"")) }
            assertNotNull(process)
            client.waitFor { !process.isAlive }
            assertEquals(0, process.exitValue(), "started with the compositor's socket")
        }
    }
}
