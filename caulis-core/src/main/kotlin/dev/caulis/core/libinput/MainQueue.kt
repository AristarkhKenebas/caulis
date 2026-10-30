package dev.caulis.core.libinput

import dev.caulis.ffi.Upcalls
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work another thread hands to the compositor's thread, run there in the order it was added
 * (the input thread's events: a key pressed and let go must arrive in that order). [post]
 * runs a task on the compositor's thread (its loop's post): one is posted when the queue was
 * empty, and it runs everything added until then — so many events cost one wake-up.
 */
internal class MainQueue(private val post: (() -> Unit) -> Unit) {
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val scheduled = AtomicBoolean(false)

    /** Adds [task] (from any thread). */
    fun add(task: () -> Unit) {
        tasks += task
        if (scheduled.compareAndSet(false, true)) {
            post {
                // (Cleared first: what is added from now on posts again or is run below.)
                scheduled.set(false)
                runAll()
            }
        }
    }

    /** Runs what is queued now, in order (on the compositor's thread). One failing task does not stop the others. */
    fun runAll() {
        while (true) {
            val task = tasks.poll() ?: break
            try {
                task()
            } catch (e: Exception) {
                Upcalls.report("input event", e)
            }
        }
    }

    /** How many wait (for tests and the log). */
    val size: Int get() = tasks.size
}
