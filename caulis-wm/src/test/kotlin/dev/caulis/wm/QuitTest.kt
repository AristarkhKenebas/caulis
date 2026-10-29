package dev.caulis.wm

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Quitting with windows still open: no window-closed handler runs on a desktop being taken down. */
class QuitTest {

    private val harness = CaulisTest()

    @Test
    fun `windows cut off at the quit are not reported closed, isRunning says it ended`() = with(harness) {
        val closed = CopyOnWriteArrayList<String>()
        val problems = CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { line -> if ("not running" in line || "handler failed" in line) problems += line }
        try {
            withCaulis({
                // (A handler that uses the desktop, as a compositor's does.)
                onWindowClosed { window ->
                    closed += window.title
                    desktop.windows.size
                }
            }, headless = true) { caulis, client ->
                val window = client.window { it.setTitle("kept") }
                window.draw()
                client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }
                assertTrue(caulis.isRunning)
                caulis.onLoop { it.quit() }
                val deadline = System.nanoTime() + 10_000_000_000
                while (caulis.isRunning && System.nanoTime() < deadline) Thread.sleep(10)
                assertFalse(caulis.isRunning)
                // (The rest of the taking down happens right after the loop ends.)
                Thread.sleep(300)
            }
        } finally {
            dev.caulis.log.Log.listener = null
        }
        assertEquals(emptyList(), closed)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `isRunning is false before the start`() {
        assertFalse(Caulis().isRunning)
    }
}
