package dev.caulis.ffi

import dev.caulis.log.InternalCaulisApi
import dev.caulis.ffi.c.libc.LibC
import java.lang.foreign.Arena
import java.nio.file.Path

/**
 * An exclusive lock on a file, held while this object is open.
 *
 * Uses flock(), the same kind of lock libwayland uses for `wayland-N.lock`, so Caulis
 * and other compositors see each other's locks.
 */
@InternalCaulisApi
class FileLock private constructor(private val fd: Fd) : AutoCloseable {

    override fun close() = fd.close() // closing the file releases the lock

    companion object {
        /** Creates [path] if needed and locks it; returns null if another process holds the lock. */
        fun tryLock(path: Path): FileLock? {
            val fd = Arena.ofConfined().use { temp ->
                Fd.adopt(Libc.open(temp.allocateFrom(path.toString()), LibC.O_RDWR() or LibC.O_CREAT() or LibC.O_CLOEXEC(), 0x1a0))
            }
            return if (Libc.flock(fd.value, LibC.LOCK_EX() or LibC.LOCK_NB())) {
                FileLock(fd)
            } else {
                fd.close()
                null
            }
        }
    }
}
