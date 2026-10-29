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

    // A descriptor's handler, and the iteration it was added in.
    private class Registration(val handler: (Readiness) -> Unit, val since: Long)

    private val handlers = HashMap<Int, Registration>()
    private var iteration = 0L
    private val log = dev.caulis.log.Logger("loop")
    // (The loop's own area in the log: "native".)
    private val stallLog = dev.caulis.log.Logger("native")

    /**
     * What happens when a handler, timer or task throws (it is logged and the loop goes on
     * with the next one; only running out of memory ends the loop).
     */
    var onError: (what: String, error: Throwable) -> Unit = { what, error -> log.error("$what failed (the loop goes on): $error", error) }
    private val beforeWaitTasks = ArrayList<() -> Unit>()

    /**
     * A handler, timer or task that kept the loop this long (milliseconds) or longer is told to
     * [onStall]: meanwhile nothing else ran — no input, no frames (0: none told). 50 by default:
     * three frames at 60 Hz.
     */
    @Volatile
    var stallMillis: Long = 50

    /**
     * Told of each run of [stallMillis] or longer: what it was (its kind and the code it came
     * from) and for how long. By default logged (area "native", level 2; at most one line a
     * second, with how many more there were).
     */
    var onStall: (what: String, millis: Long) -> Unit = { what, millis -> stallLog(what, millis) }

    /**
     * Where the task running now came from (its code: a lambda, an object), for [onStall]: one
     * that only wraps another (a library's timer around its user's code) sets it to what it
     * wraps. Cleared before each one.
     */
    var taskOrigin: Any? = null

    /** The longest run so far (milliseconds) and what it was: see [stallMillis]. */
    var longestStallMillis: Long = 0
        private set
    var longestStall: String? = null
        private set

    // (At most one line a second: the time of the last, and how many were left out since.)
    private var lastStallLine = 0L
    private var stallsLeftOut = 0

    private fun stallLog(what: String, millis: Long) {
        val now = System.nanoTime()
        if (lastStallLine != 0L && now - lastStallLine < 1_000_000_000L) {
            stallsLeftOut++
            return
        }
        val more = if (stallsLeftOut > 0) " ($stallsLeftOut more in the last second)" else ""
        stallsLeftOut = 0
        lastStallLine = now
        stallLog.info { "The main loop was busy for $millis ms: $what$more" }
    }

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
        handlers[fd.value] = Registration(handler, iteration)
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

    // Tasks handed over from other threads (see post).
    private val posted = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    // A pipe that wakes the loop when a task is posted (one byte until the loop read it).
    private val wakeRead: Fd
    private val wakeWrite: Fd
    private val wakePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val wakeByte = java.lang.foreign.Arena.global().allocate(1)
    private val wakeBuffer = java.lang.foreign.Arena.ofAuto().allocate(64)

    init {
        val (read, write) = Fd.pipe()
        wakeRead = read
        wakeWrite = write
        read.setNonBlocking()
        write.setNonBlocking()
        add(read) {
            wakePending.set(false)
            try {
                while (wakeRead.read(wakeBuffer) > 0) Unit
            } catch (_: dev.caulis.ffi.NativeException) {
                // EAGAIN: all read
            }
        }
    }

    /**
     * Runs [task] on this loop's thread, soon (the loop wakes up for it). The only method that
     * may be called from another thread (besides [stop]).
     */
    fun post(task: () -> Unit) {
        posted += task
        if (wakePending.compareAndSet(false, true)) {
            try {
                wakeWrite.write(wakeByte)
            } catch (_: Exception) {
                // Full (the loop wakes anyway) or closed (nothing runs any more).
            }
        }
    }

    /** Runs [task] once on this loop, [delayMillis] from now. */
    fun after(delayMillis: Long, task: () -> Unit): Timer =
        Timer(System.nanoTime() + delayMillis * 1_000_000, timerCount++, task).also { timers += it }

    /**
     * One iteration: run the before-wait tasks, wait up to [timeoutMillis] (less if a timer
     * is due sooner), handle what is ready, then run the timers that are due.
     */
    fun dispatch(timeoutMillis: Int) {
        for (task in beforeWaitTasks.toList()) guarded("a before-wait task", task) { task() }
        val wait = timers.peek()?.let { next ->
            val untilDue = (next.dueNanos - System.nanoTime() + 999_999) / 1_000_000
            untilDue.coerceIn(0, timeoutMillis.toLong()).toInt()
        } ?: timeoutMillis
        val batch = ++iteration
        epoll.wait(wait) { fd, ready ->
            // A descriptor added during this batch got its number from one closed earlier in
            // it: the event is the old one's, not its.
            val registration = handlers[fd]?.takeIf { it.since < batch } ?: return@wait
            guarded("the handler of descriptor $fd", registration.handler) { registration.handler(ready) }
        }
        while (true) {
            val task = posted.poll() ?: break
            guarded("a posted task", task) { task() }
        }
        val now = System.nanoTime()
        while (true) {
            val next = timers.peek() ?: break
            if (next.dueNanos > now) break
            timers.poll()
            guarded("a timer", next.task) { next.task() }
        }
        lastIterationNanos = System.nanoTime()
    }

    // Runs [body]; what it throws is reported, not passed on (one broken part never stops
    // the others) — except running out of memory, after which nothing can be trusted.
    private inline fun guarded(what: String, code: Any, body: () -> Unit) {
        taskOrigin = null
        val started = System.nanoTime()
        try {
            body()
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            try {
                onError(what, e)
            } catch (_: Throwable) {
            }
        }
        val limit = stallMillis
        if (limit > 0) {
            val millis = (System.nanoTime() - started) / 1_000_000
            if (millis >= limit) stalled(what, taskOrigin ?: code, millis)
        }
    }

    private fun stalled(what: String, code: Any, millis: Long) {
        val text = "$what (${codeName(code)})"
        if (millis > longestStallMillis) {
            longestStallMillis = millis
            longestStall = text
        }
        try {
            onStall(text, millis)
        } catch (_: Throwable) {
        }
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

    companion object {
        /**
         * The name of [code]'s class, short: "newm.Warmup" for a lambda written in newm.Warmup
         * (Java names them "newm.Warmup$$Lambda/0x…", Kotlin "newm.Warmup$step$1").
         */
        @JvmStatic
        fun codeName(code: Any): String {
            val name = code.javaClass.name
            return name.substringBefore("$$").substringBefore('/')
        }
    }

    override fun close() {
        remove(wakeRead)
        wakeRead.close()
        wakeWrite.close()
        epoll.close()
    }
}
