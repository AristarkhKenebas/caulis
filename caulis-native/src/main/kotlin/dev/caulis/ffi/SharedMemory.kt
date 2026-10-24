package dev.caulis.ffi

import dev.caulis.ffi.c.libc.LibC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Memory shared with another process through a file descriptor.
 *
 * Wayland programs draw into such memory and hand its descriptor to the compositor;
 * the compositor uses it the other way round, e.g. to send keyboard maps.
 *
 * Safety: the other process may shrink the file after handing it over. Reading past
 * the new end would crash a C compositor (SIGBUS). Here the memory is mapped through
 * the JVM, which turns that fault into an exception: [read] and [write] convert it to
 * [SharedMemoryException], so only the misbehaving program is affected.
 */
class SharedMemory private constructor(
    /** The descriptor to send to other processes. Owned by this object. */
    val fd: Fd,
    /** Size in bytes. */
    val size: Long,
    private val arena: Arena,
    private val memory: MemorySegment,
) : AutoCloseable {

    /** Runs [action] with the memory; a fault caused by the other process becomes [SharedMemoryException]. */
    fun <T> read(action: (MemorySegment) -> T): T = guarded { action(memory.asReadOnly()) }

    /** Like [read], but the memory may be changed. */
    fun <T> write(action: (MemorySegment) -> T): T = guarded { action(memory) }

    private inline fun <T> guarded(action: () -> T): T {
        try {
            return action()
        } catch (e: InternalError) {
            // The JVM reports a SIGBUS on mapped memory as InternalError.
            throw SharedMemoryException("Shared memory became unreadable (was it shrunk by its owner?)", e)
        }
    }

    override fun close() {
        arena.close()
        fd.close()
    }

    companion object {
        /**
         * Creates new shared memory of [size] bytes, filled with zeros.
         * Its size is sealed: nobody (not even the receiver) can shrink or grow it.
         */
        fun create(name: String, size: Long): SharedMemory {
            require(size > 0) { "Size must be positive, got $size" }
            val fd = Arena.ofConfined().use { temp ->
                Fd.adopt(Libc.memfdCreate(temp.allocateFrom(name), LibC.MFD_CLOEXEC() or LibC.MFD_ALLOW_SEALING()))
            }
            try {
                Libc.ftruncate(fd.value, size)
                val seals = LibC.F_SEAL_SHRINK() or LibC.F_SEAL_GROW() or LibC.F_SEAL_SEAL()
                Libc.fcntl(fd.value, LibC.F_ADD_SEALS(), seals)
                return map(fd, size, writable = true)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }

        /**
         * Maps memory received from another process. Takes ownership of [fd].
         * Fails if the file is smaller than [size] (the other process lied about it).
         */
        fun map(fd: Fd, size: Long, writable: Boolean = false): SharedMemory {
            require(size > 0) { "Size must be positive, got $size" }
            val arena = Arena.ofShared()
            try {
                // Opening /proc/self/fd/N gives a FileChannel for an existing descriptor.
                val options = if (writable) {
                    arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
                } else {
                    arrayOf(StandardOpenOption.READ)
                }
                val memory = FileChannel.open(Path.of("/proc/self/fd/${fd.value}"), *options).use { channel ->
                    if (channel.size() < size) {
                        throw SharedMemoryException("File has ${channel.size()} bytes, but $size were promised")
                    }
                    val mode = if (writable) FileChannel.MapMode.READ_WRITE else FileChannel.MapMode.READ_ONLY
                    channel.map(mode, 0, size, arena)
                }
                return SharedMemory(fd, size, arena, memory)
            } catch (e: Exception) {
                arena.close()
                fd.close()
                throw e
            }
        }
    }
}

/** Shared memory could not be used safely; the process that sent it should be disconnected. */
class SharedMemoryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
