package dev.caulis.ffi

import dev.caulis.ffi.c.libc.LibC

/**
 * An open file descriptor that this program owns.
 *
 * The rule is simple: whoever holds an [Fd] must close it, and it is closed exactly once.
 * Use it with `use { }` so it is closed even when something throws:
 *
 * ```
 * Fd.adopt(raw).use { fd -> ... }
 * ```
 *
 * To hand the descriptor to someone else (for example, to send it to another process),
 * call [release]: after that this object no longer owns it and will not close it.
 */
class Fd private constructor(private var number: Int, private val owned: Boolean = true) : AutoCloseable {

    /** The raw descriptor number. Throws if this [Fd] was already closed or released. */
    val value: Int
        get() {
            check(number >= 0) { "File descriptor is already closed or released" }
            return number
        }

    val isOpen: Boolean get() = number >= 0

    /** Gives up ownership and returns the raw number; this object will not close it. */
    fun release(): Int {
        val raw = value
        number = -1
        return raw
    }

    /**
     * Size of the file in bytes (works for GPU buffers too), or null if it has none
     * (pipes, sockets). Moves the file position, so only use it on files you do not read.
     */
    fun sizeOrNull(): Long? = try {
        Libc.lseek(value, 0, SEEK_END)
    } catch (e: NativeException) {
        null
    }

    /** A second, independent descriptor for the same file (closed separately). */
    /** Reads into [buffer]: the number of bytes read, 0 at the end; throws [NativeException] (EAGAIN: nothing yet). */
    fun read(buffer: java.lang.foreign.MemorySegment): Long = Libc.read(value, buffer)

    /** Writes [data]: the number of bytes written (maybe fewer); throws [NativeException] (EAGAIN: full for now). */
    fun write(data: java.lang.foreign.MemorySegment): Long = Libc.write(value, data)

    /** Makes reads and writes return at once instead of waiting (they fail with EAGAIN then). */
    fun setNonBlocking() {
        Libc.fcntl(value, LibC.F_SETFL(), Libc.fcntl(value, LibC.F_GETFL()) or LibC.O_NONBLOCK())
    }

    fun duplicate(): Fd = adopt(Libc.fcntl(value, LibC.F_DUPFD_CLOEXEC(), 0))

    override fun close() {
        if (number < 0) return
        val raw = number
        number = -1
        if (owned) Libc.close(raw)
    }

    override fun toString(): String = if (isOpen) "Fd($number)" else "Fd(closed)"

    companion object {
        private const val SEEK_END = 2 // from <unistd.h>; the same on every Linux

        /** Takes ownership of a raw descriptor number returned by a C call. */
        fun adopt(raw: Int): Fd {
            require(raw >= 0) { "Not a file descriptor: $raw" }
            return Fd(raw)
        }

        /** A new pipe: (the end to read, the end to write), both closed in started programs. */
        fun pipe(): Pair<Fd, Fd> = java.lang.foreign.Arena.ofConfined().use { temp ->
            val ends = temp.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 2)
            Libc.pipe2(ends, LibC.O_CLOEXEC())
            adopt(ends.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0)) to adopt(ends.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 1))
        }

        /**
         * A descriptor that belongs to a C library (e.g. libseat's or libinput's own socket):
         * we may watch it in the event loop, but [close] leaves it open for its owner.
         */
        fun borrow(raw: Int): Fd {
            require(raw >= 0) { "Not a file descriptor: $raw" }
            return Fd(raw, owned = false)
        }
    }
}
