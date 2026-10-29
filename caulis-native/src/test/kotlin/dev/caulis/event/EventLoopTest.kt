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

    @Test
    fun `a task that holds the loop is reported with the code it came from, quick ones are not`() {
        EventLoop().use { loop ->
            val stalls = ArrayList<Pair<String, Long>>()
            loop.stallMillis = 40
            loop.onStall = { what, millis -> stalls += what to millis }
            loop.after(1) { Thread.sleep(60) }
            loop.after(2) { }
            val origin = Any()
            loop.post { loop.taskOrigin = origin; Thread.sleep(45) }
            loop.after(150) { loop.stop() }
            loop.run()
            assertEquals(2, stalls.size, "$stalls")
            assertTrue(stalls.any { (what, ms) -> what.startsWith("a timer (dev.caulis.event.EventLoopTest") && ms >= 60 }, "$stalls")
            assertTrue(stalls.any { (what, _) -> what == "a posted task (java.lang.Object)" }, "$stalls")
            assertTrue(loop.longestStallMillis >= 60)
            assertEquals(stalls.maxBy { it.second }.first, loop.longestStall)
        }
    }

    @Test
    fun `no stall report at 0`() {
        EventLoop().use { loop ->
            var told = false
            loop.stallMillis = 0
            loop.onStall = { _, _ -> told = true }
            loop.after(1) { Thread.sleep(30) }
            loop.after(40) { loop.stop() }
            loop.run()
            assertEquals(false, told)
        }
    }

    @Test
    fun `the C library's memory can be given back where it is glibc`() {
        val freed = dev.caulis.ffi.NativeMemory.trim()
        if (dev.caulis.ffi.NativeMemory.canTrim) assertTrue(freed >= 0) else assertEquals(-1L, freed)
        assertTrue(dev.caulis.ffi.NativeMemory.residentKb() > 0)
        if (dev.caulis.ffi.NativeMemory.canTrim) assertTrue(dev.caulis.ffi.NativeMemory.limitPools(8))
    }
}
