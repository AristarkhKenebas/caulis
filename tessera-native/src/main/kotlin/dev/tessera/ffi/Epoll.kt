package dev.tessera.ffi

import dev.tessera.ffi.c.libc.LibC
import dev.tessera.ffi.c.libc.epoll_data
import dev.tessera.ffi.c.libc.epoll_event
import java.lang.foreign.Arena

/**
 * Waits for many file descriptors at once: "tell me when any of these has something
 * to read, or can be written to". This is the heart of an event loop.
 *
 * ```
 * Epoll().use { epoll ->
 *     epoll.add(socket.fd, read = true)
 *     epoll.wait(timeoutMillis = 1000) { fd, ready -> if (ready.readable) ... }
 * }
 * ```
 *
 * Not thread-safe: use one [Epoll] from one thread (the event loop thread).
 */
class Epoll : AutoCloseable {

    private val epoll = Fd.adopt(Libc.epollCreate(LibC.EPOLL_CLOEXEC()))

    // Memory for talking to the kernel, allocated once and reused on every call,
    // so waiting for events creates no garbage.
    private val arena = Arena.ofShared()
    private val event = epoll_event.allocate(arena)
    private val readyEvents = epoll_event.allocateArray(MAX_EVENTS.toLong(), arena)

    /** Starts watching [fd]. */
    fun add(fd: Fd, read: Boolean = true, write: Boolean = false) =
        control(LibC.EPOLL_CTL_ADD(), fd, read, write)

    /** Changes what is watched on [fd] (for example, start waiting until it is writable). */
    fun modify(fd: Fd, read: Boolean = true, write: Boolean = false) =
        control(LibC.EPOLL_CTL_MOD(), fd, read, write)

    /** Stops watching [fd]. Call this before closing it. */
    fun remove(fd: Fd) {
        Libc.epollCtl(epoll.value, LibC.EPOLL_CTL_DEL(), fd.value, event)
    }

    private fun control(operation: Int, fd: Fd, read: Boolean, write: Boolean) {
        var interest = LibC.EPOLLRDHUP()
        if (read) interest = interest or LibC.EPOLLIN()
        if (write) interest = interest or LibC.EPOLLOUT()
        epoll_event.events(event, interest)
        epoll_data.fd(epoll_event.data(event), fd.value)
        Libc.epollCtl(epoll.value, operation, fd.value, event)
    }

    /**
     * Waits until at least one watched descriptor is ready, or [timeoutMillis] passes
     * (-1 = wait forever, 0 = just check). Calls [onReady] for each ready descriptor
     * with its raw number. Returns how many were ready.
     */
    fun wait(timeoutMillis: Int, onReady: (fd: Int, ready: Readiness) -> Unit): Int {
        val count = Libc.epollWait(epoll.value, readyEvents, MAX_EVENTS, timeoutMillis)
        for (i in 0 until count) {
            val item = epoll_event.asSlice(readyEvents, i.toLong())
            val fd = epoll_data.fd(epoll_event.data(item))
            onReady(fd, Readiness(epoll_event.events(item)))
        }
        return count
    }

    override fun close() {
        epoll.close()
        arena.close()
    }

    private companion object {
        // How many ready descriptors one wait() can report; more simply wait for the next call.
        const val MAX_EVENTS = 64
    }
}

/** What happened to a watched descriptor. Wraps the kernel's bit mask without allocating. */
@JvmInline
value class Readiness(private val bits: Int) {
    /** There is data to read (or a connection to accept). */
    val readable: Boolean get() = bits and LibC.EPOLLIN() != 0

    /** Writing will not block. */
    val writable: Boolean get() = bits and LibC.EPOLLOUT() != 0

    /** The other side closed the connection. */
    val hangUp: Boolean get() = bits and (LibC.EPOLLHUP() or LibC.EPOLLRDHUP()) != 0

    /** The descriptor is in an error state. */
    val error: Boolean get() = bits and LibC.EPOLLERR() != 0
}
