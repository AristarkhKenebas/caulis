package dev.tessera.core

import dev.tessera.ffi.SharedMemoryException
import dev.tessera.protocol.wayland.WlShm
import dev.tessera.render.Renderer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Copies committed pictures to the GPU.
 *
 * Only the rows the client marked as changed (damage) are copied. They go through
 * Tessera's own memory first: the GPU driver reads pixels in C code, where a client
 * shrinking its shared memory would crash the compositor; copying in Kotlin turns that
 * into an error for that one client.
 *
 * After copying, the client gets its buffer back right away (wl_buffer.release), so it
 * can draw the next frame into it without waiting for us.
 */
internal class SurfaceUploader(private val renderer: Renderer) {

    private var staging: MemorySegment = MemorySegment.NULL

    fun upload(surface: Surface) {
        val buffer = surface.current.buffer
        if (buffer == null) {
            // A null buffer means "hide me".
            surface.texture?.close()
            surface.texture = null
            surface.current.damage.clear()
            return
        }
        val shm = buffer.dataAs<ShmBuffer>()
        if (shm == null) {
            // GPU buffers (dmabuf) come with stage 4; until then nothing is shown.
            buffer.sendRelease()
            return
        }

        var texture = surface.texture
        val newSize = texture == null || texture.width != shm.width || texture.height != shm.height
        if (newSize) {
            texture?.close()
            texture = renderer.createTexture(shm.width, shm.height)
            surface.texture = texture
        }

        val (firstRow, rowCount) = if (newSize) 0 to shm.height else changedRows(surface.current.damage, shm.height)
        surface.current.damage.clear()

        if (rowCount > 0) {
            val rowBytes = shm.width.toLong() * 4
            val pixels = staging(rowBytes * rowCount)
            try {
                shm.pool.read { memory ->
                    for (row in 0 until rowCount) {
                        val from = shm.offset + (firstRow + row).toLong() * shm.stride
                        MemorySegment.copy(memory, from, pixels, row * rowBytes, rowBytes)
                    }
                }
            } catch (e: SharedMemoryException) {
                buffer.client.server.log.warn("Client ${buffer.client.pid}: ${e.message}")
                buffer.client.disconnect()
                return
            }
            texture!!.upload(pixels, firstRow, rowCount)
        }
        surface.opaque = shm.format == WlShm.Format.XRGB8888
        buffer.sendRelease()
    }

    // The band of rows covered by all damage rectangles (clamped to the picture).
    private fun changedRows(damage: List<Rect>, height: Int): Pair<Int, Int> {
        if (damage.isEmpty()) return 0 to 0
        val top = damage.minOf { it.y }.coerceIn(0, height)
        val bottom = damage.maxOf { it.y.toLong() + it.height }.coerceIn(0, height.toLong()).toInt()
        return top to (bottom - top).coerceAtLeast(0)
    }

    // A scratch buffer of at least [bytes]; replaced (and the old one left to the GC) when too small.
    private fun staging(bytes: Long): MemorySegment {
        if (staging.byteSize() < bytes) staging = Arena.ofAuto().allocate(bytes, 16)
        return staging.asSlice(0, bytes)
    }
}
