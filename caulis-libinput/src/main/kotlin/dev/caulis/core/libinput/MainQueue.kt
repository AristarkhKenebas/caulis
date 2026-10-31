package dev.caulis.core.libinput

import dev.caulis.ffi.Upcalls
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work another thread hands to the compositor's thread, run there in the order it was added
 * (the input thread's events: a key pressed and let go must arrive in that order). [post]
 * runs a task on the compositor's thread (its loop's post): one is posted when the queue was
 * empty, and it runs everything added until then — so many events cost one wake-up.
 *
 * Pointer movements ([Merging]) still waiting take in the next ones of their kind: while the
 * compositor's thread is busy, a mouse moving fast queues one movement (the sum, or the last
 * place), not thousands each run alone afterwards. Everything else is run as it came.
 */
internal class MainQueue(private val post: (() -> Unit) -> Unit) {
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val scheduled = AtomicBoolean(false)

    // How many wait (the queue's own size counts them all, each time).
    private val waiting = java.util.concurrent.atomic.AtomicInteger()

    // The task added last (by the adding thread): a movement may still take in the next one.
    private var last: Merging? = null
    private val adding = Any()

    /**
     * A task that can take in the next one of its kind while neither has begun to run (two
     * relative movements summed; two absolute ones: the later place). Taking in and starting
     * are guarded by the task itself.
     */
    abstract class Merging : () -> Unit {
        private var started = false

        /** How many movements this one stands for now (1, more once others were taken in). */
        var count = 1
            private set

        /** Takes [next] into this one if it is of the same kind; false if not. */
        protected abstract fun take(next: Merging): Boolean

        /** Runs it (once, with what it took in). */
        protected abstract fun run()

        internal fun merge(next: Merging): Boolean = synchronized(this) {
            if (started || !take(next)) return false
            count += next.count
            true
        }

        final override fun invoke() {
            synchronized(this) { started = true }
            run()
        }
    }

    /** Adds [task] (from any thread). */
    fun add(task: () -> Unit) {
        val count = synchronized(adding) {
            val before = last
            if (task is Merging && before != null && before.merge(task)) return
            last = task as? Merging
            waiting.incrementAndGet().also { tasks += task }
        }
        if (count == LONG_QUEUE) log.warn("$LONG_QUEUE input events wait for the compositor's thread (it is busy)")
        if (scheduled.compareAndSet(false, true)) {
            post {
                // (Cleared first: what is added from now on posts again or is run below.)
                scheduled.set(false)
                runAll()
            }
        }
    }

    /**
     * Runs what is queued now, in order (on the compositor's thread). One failing task does not
     * stop the others. What is added meanwhile waits for the next turn of the loop (it posted
     * its own run): a mouse reporting faster than its events are handled never keeps the loop
     * from sending the programs what happened and drawing frames.
     */
    fun runAll() {
        // (Only as many as wait now: what is added meanwhile has posted its own run.)
        repeat(waiting.get()) {
            val task = tasks.poll() ?: return
            waiting.decrementAndGet()
            try {
                task()
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                Upcalls.report("input event", e)
            }
        }
    }

    /** How many wait (for tests and the log). */
    val size: Int get() = waiting.get()

    private companion object {
        // Told when this many wait (movements are merged: keys and buttons so many is unusual).
        const val LONG_QUEUE = 1000
        val log = dev.caulis.log.Logger("input")
    }
}
