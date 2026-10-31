package dev.caulis.core

import dev.caulis.ffi.SharedMemoryException
import dev.caulis.geometry.Rect
import dev.caulis.protocol.wayland.WlShm
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Copies committed pictures to the GPU.
 *
 * Only the parts the client marked as changed (damage) are copied. They go through
 * Caulis's own memory first: the GPU driver reads pixels in C code, where a client
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
        val gpu = buffer?.dataAs<DmabufBuffer>()
        // Whatever comes now replaces the GPU buffer shown before (the same one is kept).
        val previous = surface.shownDmabuf
        if (previous !== gpu) {
            gpu?.startShowing()
            surface.shownDmabuf = gpu
            previous?.stopShowing()
        }
        if (buffer == null) {
            // A null buffer means "hide me".
            surface.texture = null
            surface.current.damageList.clear()
            return
        }
        if (gpu != null) {
            // Already on the GPU: show the buffer itself, nothing to copy.
            surface.texture = gpu.texture
            surface.opaque = gpu.opaque
            surface.current.damageList.clear()
            return
        }
        val shm = buffer.dataAs<ShmBuffer>()
        if (shm == null) {
            buffer.sendRelease()
            return
        }

        var texture = surface.uploadedTexture
        val fullUpload = texture == null || texture.width != shm.width || texture.height != shm.height ||
            surface.texture !== texture
        if (texture == null || texture.width != shm.width || texture.height != shm.height) {
            texture?.close()
            texture = renderer.createTexture(shm.width, shm.height)
            surface.uploadedTexture = texture
        }
        surface.texture = texture

        val parts = if (fullUpload) listOf(Rect(0, 0, shm.width, shm.height)) else partsToCopy(surface.current.damage, shm.width, shm.height)
        surface.current.damageList.clear()
        for (part in parts) if (!copy(part, shm, buffer, texture)) return
        surface.opaque = shm.format == WlShm.Format.XRGB8888
        buffer.sendRelease()
    }

    // Copies [part] of the picture, its rows packed tight (false: there is nothing more to do for
    // this picture). In bands of rows that fit the staging memory (never more than a 4K picture's):
    // a huge picture costs time, not gigabytes at once.
    private fun copy(part: Rect, shm: ShmBuffer, buffer: dev.caulis.protocol.wayland.WlBuffer, texture: GpuTexture): Boolean {
        val rowBytes = part.width.toLong() * 4
        val band = (KEEP_STAGING / rowBytes).toInt().coerceIn(1, part.height)
        var done = 0
        while (done < part.height) {
            val rows = minOf(band, part.height - done)
            val pixels = staging(rowBytes * rows)
            val top = part.y + done
            try {
                shm.pool.read { memory ->
                    // Rows one after the other in the program's memory: one copy for the band.
                    if (shm.stride.toLong() == rowBytes) {
                        MemorySegment.copy(memory, shm.offset + top.toLong() * shm.stride, pixels, 0, rowBytes * rows)
                    } else for (row in 0 until rows) {
                        val from = shm.offset + (top + row).toLong() * shm.stride + part.x * 4L
                        MemorySegment.copy(memory, from, pixels, row * rowBytes, rowBytes)
                    }
                    Unit
                }
            } catch (e: SharedMemoryException) {
                buffer.client.server.log.warn("Client ${buffer.client.pid}: ${e.message}")
                buffer.client.disconnect()
                return false
            } catch (e: IllegalStateException) {
                // Its pool was destroyed after the attach (allowed): there is nothing to show.
                return false
            }
            texture.uploadPart(pixels, part.x, top, part.width, rows)
            bytesCopied += rowBytes * rows
            done += rows
        }
        return true
    }

    /** Bytes of pictures copied so far (for the render log: what programs' drawing costs). */
    var bytesCopied = 0L
        private set

    // A scratch buffer of at least [bytes]; replaced (and the old one left to the GC) when too small.
    private fun staging(bytes: Long): MemorySegment {
        // Bigger when needed; back to a usual size after one huge picture (it is not kept).
        if (staging.byteSize() < bytes || (staging.byteSize() > KEEP_STAGING && bytes <= KEEP_STAGING)) {
            staging = Arena.ofAuto().allocate(bytes, 16)
        }
        return staging.asSlice(0, bytes)
    }

    private companion object {
        // A 4K picture's rows: what the staging memory may stay at.
        const val KEEP_STAGING = 3840L * 2160 * 4
    }
}

/**
 * What to copy for [damage] of a [width] x [height] picture: the parts inside it, those touching
 * each other as one, so no pixel goes twice; no more than [maxParts], and when one box around them
 * all is hardly bigger, that one (fewer calls).
 */
internal fun partsToCopy(damage: List<Rect>, width: Int, height: Int, maxParts: Int = 8): List<Rect> {
    val whole = Rect(0, 0, width, height)
    val parts = ArrayList<Rect>()
    for (rect in damage) {
        var merged = rect.intersect(whole)?.takeIf { !it.isEmpty } ?: continue
        var again = true
        while (again) {
            again = false
            val iterator = parts.iterator()
            while (iterator.hasNext()) {
                val part = iterator.next()
                if (part.intersect(merged) != null) {
                    merged = merged.union(part)
                    iterator.remove()
                    again = true
                }
            }
        }
        parts += merged
    }
    if (parts.size <= 1) return parts
    val box = parts.reduce(Rect::union)
    val area = parts.sumOf { it.width.toLong() * it.height }
    return if (parts.size > maxParts || box.width.toLong() * box.height <= area + area / 4) listOf(box) else parts
}
