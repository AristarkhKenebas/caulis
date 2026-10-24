package dev.caulis.event

import dev.caulis.ffi.Epoll
import dev.caulis.ffi.Fd
import dev.caulis.ffi.Readiness

/**
 * The main loop: waits for file descriptors to become ready and calls their handlers.
 *
 * Everything in a compositor (clients, input devices, the display) is a file descriptor,
 * so one loop on one thread drives it all. Handlers must return quickly and never block:
 * a slow handler delays every frame.
 *
 * ```
 * EventLoop().use { loop ->
 *     loop.add(socket.fd) { ready -> if (ready.readable) ... }
 *     loop.run()   // until loop.stop()
 * }
 * ```
 */
class EventLoop : AutoCloseable {

    private val epoll = Epoll()
    private val handlers = HashMap<Int, (Readiness) -> Unit>()
    private val beforeWaitTasks = ArrayList<() -> Unit>()

    // Timers, the earliest first. Kept in Kotlin: the wait below simply ends in time for the next one.
    private val timers = java.util.PriorityQueue<Timer>(compareBy<Timer> { it.dueNanos }.thenBy { it.order })
    private var timerCount = 0L

    /** A task that runs once, later ([after]). [cancel] stops it if it has not run yet. */
    inner class Timer internal constructor(val dueNanos: Long, internal val order: Long, internal val task: () -> Unit) {
        fun cancel() {
            timers.remove(this)
        }
    }

    /**
     * When the loop last finished an iteration (System.nanoTime). A watchdog on another
     * thread can read it to notice a loop that is stuck.
     */
    @Volatile
    var lastIterationNanos: Long = System.nanoTime()
        private set

    @Volatile
    private var running = false

    /** Calls [handler] whenever [fd] is ready. */
    fun add(fd: Fd, read: Boolean = true, write: Boolean = false, handler: (Readiness) -> Unit) {
        epoll.add(fd, read, write)
        handlers[fd.value] = handler
    }

    /** Changes whether [fd] is watched for reading and/or writing. */
    fun update(fd: Fd, read: Boolean = true, write: Boolean = false) {
        epoll.modify(fd, read, write)
    }

    /** Stops watching [fd]. Must be called before [fd] is closed. */
    fun remove(fd: Fd) {
        if (handlers.remove(fd.value) != null) epoll.remove(fd)
    }

    /**
     * Runs [task] on every iteration, just before the loop goes to sleep. Used for work
     * that should be batched, like sending all queued messages to clients at once.
     */
    fun beforeWait(task: () -> Unit) {
        beforeWaitTasks += task
    }

    /** Runs [task] once on this loop, [delayMillis] from now. */
    fun after(delayMillis: Long, task: () -> Unit): Timer =
        Timer(System.nanoTime() + delayMillis * 1_000_000, timerCount++, task).also { timers += it }

    /**
     * One iteration: run the before-wait tasks, wait up to [timeoutMillis] (less if a timer
     * is due sooner), handle what is ready, then run the timers that are due.
     */
    fun dispatch(timeoutMillis: Int) {
        for (task in beforeWaitTasks) task()
        val wait = timers.peek()?.let { next ->
            val untilDue = (next.dueNanos - System.nanoTime() + 999_999) / 1_000_000
            untilDue.coerceIn(0, timeoutMillis.toLong()).toInt()
        } ?: timeoutMillis
        epoll.wait(wait) { fd, ready -> handlers[fd]?.invoke(ready) }
        val now = System.nanoTime()
        while (true) {
            val next = timers.peek() ?: break
            if (next.dueNanos > now) break
            timers.poll()
            next.task()
        }
        lastIterationNanos = System.nanoTime()
    }

    /** Runs until [stop] is called (from any thread). */
    fun run() {
        running = true
        while (running) {
            // Wake up regularly so a stop() from another thread is noticed quickly.
            dispatch(timeoutMillis = 100)
        }
    }

    fun stop() {
        running = false
    }

    override fun close() {
        epoll.close()
    }
}
