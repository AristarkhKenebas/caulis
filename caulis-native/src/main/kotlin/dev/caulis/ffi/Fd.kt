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
class Fd private constructor(private var number: Int) : AutoCloseable {

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
    fun duplicate(): Fd = adopt(Libc.fcntl(value, LibC.F_DUPFD_CLOEXEC(), 0))

    override fun close() {
        if (number < 0) return
        val raw = number
        number = -1
        Libc.close(raw)
    }

    override fun toString(): String = if (isOpen) "Fd($number)" else "Fd(closed)"

    companion object {
        private const val SEEK_END = 2 // from <unistd.h>; the same on every Linux

        /** Takes ownership of a raw descriptor number returned by a C call. */
        fun adopt(raw: Int): Fd {
            require(raw >= 0) { "Not a file descriptor: $raw" }
            return Fd(raw)
        }
    }
}
