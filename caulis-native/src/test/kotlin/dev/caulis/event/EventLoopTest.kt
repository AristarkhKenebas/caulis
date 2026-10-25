package dev.caulis.event

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLoopTest {

    @Test
    fun `timers run in order, cancelled ones never`() {
        EventLoop().use { loop ->
            val ran = ArrayList<String>()
            loop.after(30) { ran += "second" }
            loop.after(10) { ran += "first" }
            loop.after(20) { ran += "cancelled" }.cancel()
            loop.after(50) { loop.stop() }
            val start = System.nanoTime()
            loop.run()
            assertEquals(listOf("first", "second"), ran)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "timers should not wait for the 100 ms poll")
        }
    }

    @Test
    fun `a timer or task that throws - even an Error - is reported, the others still run`() {
        EventLoop().use { loop ->
            val ran = ArrayList<String>()
            val failed = ArrayList<String>()
            loop.onError = { what, e -> failed += "$what: ${e.javaClass.simpleName}" }
            loop.after(5) { throw IllegalStateException("broken") }
            loop.after(6) { throw StackOverflowError() }
            loop.after(7) { ran += "after them" }
            loop.post { throw NoClassDefFoundError("libxcb-composite") }
            loop.after(30) { loop.stop() }
            loop.run()
            assertEquals(listOf("after them"), ran)
            assertEquals(listOf("a posted task: NoClassDefFoundError", "a timer: IllegalStateException", "a timer: StackOverflowError"), failed)
        }
    }

    @Test
    fun `a task posted from another thread wakes the loop at once`() {
        EventLoop().use { loop ->
            var ran = false
            loop.dispatch(0)
            Thread { Thread.sleep(50); loop.post { ran = true } }.start()
            val start = System.nanoTime()
            while (!ran) loop.dispatch(5_000)
            val millis = (System.nanoTime() - start) / 1_000_000
            kotlin.test.assertTrue(millis < 1_000, "woke after $millis ms")
        }
    }

    @Test
    fun `the watchdog notices a stuck loop`() {
        EventLoop().use { loop ->
            val stuck = CountDownLatch(1)
            Watchdog(loop, warnMillis = 200, giveUpMillis = 600, onStuck = { stuck.countDown() }).use {
                loop.after(0) { Thread.sleep(2_000) } // a handler that never returns in time
                loop.after(10) { loop.stop() }
                val thread = Thread.ofPlatform().start { loop.run() }
                assertTrue(stuck.await(5, TimeUnit.SECONDS), "the watchdog should have given up")
                thread.join() // the loop finishes before it is closed
            }
        }
    }
}
