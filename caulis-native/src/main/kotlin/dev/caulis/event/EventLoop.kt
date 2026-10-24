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

    /** One iteration: run the before-wait tasks, wait up to [timeoutMillis], handle what is ready. */
    fun dispatch(timeoutMillis: Int) {
        for (task in beforeWaitTasks) task()
        epoll.wait(timeoutMillis) { fd, ready -> handlers[fd]?.invoke(ready) }
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
