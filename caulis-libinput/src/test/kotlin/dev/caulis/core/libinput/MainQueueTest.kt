package dev.caulis.core.libinput

import dev.caulis.event.EventLoop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The input thread's events reach the compositor's thread all, in order, with few wake-ups. */
class MainQueueTest {

    @Test
    fun `events from another thread arrive in order`() = EventLoop().use { loop ->
        var posts = 0
        val queue = MainQueue { task -> posts++; loop.post(task) }
        val seen = ArrayList<Int>()
        val count = 20_000
        val producer = Thread {
            for (i in 0 until count) queue.add { seen += i }
        }
        producer.start()
        val until = System.nanoTime() + 10_000_000_000L
        while (seen.size < count && System.nanoTime() < until) loop.dispatch(10)
        producer.join()
        assertEquals(count, seen.size, "all arrived")
        assertEquals((0 until count).toList(), seen, "in the order they were read")
        assertTrue(posts < count, "one wake-up for many events ($posts posts)")
    }

    @Test
    fun `events added while the queue runs wait for the next turn of the loop`() = EventLoop().use { loop ->
        val queue = MainQueue(loop::post)
        var timers = 0
        var added = 0
        // (A mouse faster than its events are handled: each one handled adds another.)
        fun event() {
            if (++added < 10_000) queue.add { event() }
        }
        queue.add { event() }
        loop.after(0) { timers++ }
        loop.dispatch(0)
        assertTrue(added < 10, "one turn ran what was there, not everything added meanwhile ($added)")
        assertEquals(1, timers, "the loop went on to its timers")
        val until = System.nanoTime() + 10_000_000_000L
        while (added < 10_000 && System.nanoTime() < until) loop.dispatch(10)
        assertEquals(10_000, added, "the rest came in the next turns")
    }

    @Test
    fun `one failing event does not stop the others`() = EventLoop().use { loop ->
        val queue = MainQueue(loop::post)
        val seen = ArrayList<Int>()
        queue.add { seen += 1 }
        queue.add { error("broken") }
        queue.add { seen += 3 }
        loop.dispatch(0)
        assertEquals(listOf(1, 3), seen)
    }

    @Test
    fun `what waits can be run by hand, as when the input thread is being stopped`() = EventLoop().use { loop ->
        // (stopThread runs the queue itself while it waits for the thread: a device the input
        // thread is opening must not wait for a loop that is waiting for it.)
        val queue = MainQueue(loop::post)
        val answer = java.util.concurrent.CompletableFuture<Int>()
        val waiting = Thread { queue.add { answer.complete(42) }; answer.get() }
        waiting.start()
        while (waiting.isAlive) {
            queue.runAll()
            waiting.join(10)
        }
        assertEquals(42, answer.get())
        assertEquals(0, queue.size)
    }

    // A movement as the input thread queues them: summed with the next ones still waiting.
    private class Move(var dx: Int, val handled: MutableList<Pair<Int, Int>>) : MainQueue.Merging() {
        override fun take(next: MainQueue.Merging): Boolean {
            if (next !is Move) return false
            dx += next.dx
            return true
        }

        override fun run() {
            // (Each handled movement costs as much as a pointer's hit test on a busy desktop.)
            Thread.sleep(1)
            handled += dx to count
        }
    }

    @Test
    fun `10 000 movements queued while the compositor's thread is busy are handled in bounded time, summed, in order with keys`() = EventLoop().use { loop ->
        val queue = MainQueue(loop::post)
        val handled = ArrayList<Pair<Int, Int>>()
        val keys = ArrayList<Int>()
        // (Queued from the input thread while this thread does not run the loop: busy.)
        val producer = Thread {
            for (i in 0 until 5_000) queue.add(Move(1, handled))
            queue.add { keys += handled.sumOf { it.first } }
            for (i in 0 until 5_000) queue.add(Move(1, handled))
        }
        producer.start()
        producer.join()
        assertTrue(queue.size <= 3, "movements waiting together are one: ${queue.size} queued")
        val started = System.nanoTime()
        while (queue.size > 0) loop.dispatch(10)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 1_000, "handled in $millis ms (10 000 one by one: over 10 s)")
        assertEquals(10_000, handled.sumOf { it.first }, "no movement lost")
        assertEquals(10_000, handled.sumOf { it.second }, "each counted (the early cursor catches up by as many)")
        assertEquals(listOf(5_000), keys, "the key comes after the movements before it, before those after it")
    }

    @Test
    fun `a movement already running takes no more in - the next one waits on its own`() = EventLoop().use { loop ->
        val queue = MainQueue(loop::post)
        val handled = ArrayList<Pair<Int, Int>>()
        queue.add(object : MainQueue.Merging() {
            override fun take(next: MainQueue.Merging) = true
            override fun run() {
                // (The input thread adds one meanwhile: not merged into this one, it runs after.)
                queue.add(Move(7, handled))
                handled += 1 to count
            }
        })
        while (queue.size > 0) loop.dispatch(10)
        assertEquals(listOf(1 to 1, 7 to 1), handled)
    }
}
