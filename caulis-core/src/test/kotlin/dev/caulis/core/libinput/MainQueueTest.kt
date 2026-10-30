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
}
