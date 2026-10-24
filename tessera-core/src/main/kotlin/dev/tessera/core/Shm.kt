package dev.tessera.core

import dev.tessera.ffi.Fd
import dev.tessera.ffi.SharedMemory
import dev.tessera.ffi.SharedMemoryException
import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlBuffer
import dev.tessera.protocol.wayland.WlShm
import dev.tessera.protocol.wayland.WlShmPool

/**
 * wl_shm: clients draw into shared memory and hand it to the compositor as buffers.
 *
 * Everything a client says about that memory is checked: offsets, sizes and strides must
 * stay inside the memory it actually shared (computed in Long, so huge numbers cannot
 * wrap around), and the memory itself is mapped safely ([SharedMemory]).
 */
class Shm(display: Display) {

    init {
        display.addGlobal(WlShm, VERSION) { shm ->
            shm.requests = requests
            for (format in FORMATS) shm.sendFormat(format)
        }
    }

    private val requests = object : WlShm.Requests {
        override fun createPool(resource: WlShm, id: WlShmPool, fd: Fd, size: Int) {
            if (size <= 0) {
                fd.close()
                resource.postError(WlShm.Error.INVALID_STRIDE, "pool size must be positive, got $size")
                return
            }
            val memory = try {
                SharedMemory.map(fd, size.toLong())
            } catch (e: Exception) {
                resource.postError(WlShm.Error.INVALID_FD, "cannot use the shared memory: ${e.message}")
                return
            }
            ShmPool(id, memory)
        }

        override fun release(resource: WlShm) {}
    }

    companion object {
        const val VERSION = 2

        /** Pixel formats every compositor must support. */
        val FORMATS = listOf(WlShm.Format.ARGB8888, WlShm.Format.XRGB8888)

        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * A piece of shared memory a client gave us; buffers are rectangles inside it.
 * The memory is released when the pool and all its buffers are gone.
 */
class ShmPool internal constructor(resource: WlShmPool, private var memory: SharedMemory) {

    private var users = 1 // the pool itself + every buffer made from it

    init {
        resource.requests = object : WlShmPool.Requests {
            override fun createBuffer(
                resource: WlShmPool, id: WlBuffer, offset: Int, width: Int, height: Int, stride: Int, format: Int,
            ) {
                if (format !in Shm.FORMATS) {
                    resource.postError(WlShm.Error.INVALID_FORMAT, "unsupported format $format")
                    return
                }
                if (width <= 0 || height <= 0 || offset < 0 || stride < width.toLong() * Shm.BYTES_PER_PIXEL) {
                    resource.postError(WlShm.Error.INVALID_STRIDE, "invalid buffer ${width}x$height stride $stride offset $offset")
                    return
                }
                val end = offset.toLong() + stride.toLong() * height
                if (end > memory.size) {
                    resource.postError(WlShm.Error.INVALID_STRIDE, "buffer ends at byte $end, pool has ${memory.size}")
                    return
                }
                users++
                ShmBuffer(id, this@ShmPool, offset.toLong(), width, height, stride, format)
            }

            override fun destroy(resource: WlShmPool) {}

            override fun resize(resource: WlShmPool, size: Int) {
                if (size < memory.size) {
                    resource.postError(WlShm.Error.INVALID_STRIDE, "pools can only grow ($size < ${memory.size})")
                    return
                }
                // Map the same memory again with the new size.
                val bigger = try {
                    SharedMemory.map(memory.fd.duplicate(), size.toLong())
                } catch (e: SharedMemoryException) {
                    resource.postError(WlShm.Error.INVALID_FD, "cannot resize: ${e.message}")
                    return
                }
                memory.close()
                memory = bigger
            }
        }
        resource.onDestroy { release() }
    }

    /** Runs [action] with the pool's memory (a misbehaving client causes [SharedMemoryException]). */
    fun <T> read(action: (java.lang.foreign.MemorySegment) -> T): T = memory.read(action)

    internal fun release() {
        if (--users == 0) memory.close()
    }
}

/** A client's picture: [width]x[height] pixels at [offset] in a [ShmPool]. */
class ShmBuffer internal constructor(
    val resource: WlBuffer,
    val pool: ShmPool,
    val offset: Long,
    val width: Int,
    val height: Int,
    val stride: Int,
    val format: Int,
) {
    init {
        resource.requests = object : WlBuffer.Requests {
            override fun destroy(resource: WlBuffer) {}
        }
        resource.onDestroy { pool.release() }
    }
}
