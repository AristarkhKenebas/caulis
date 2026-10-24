package dev.caulis.core.nested

import dev.caulis.ffi.Fd
import dev.caulis.ffi.SharedMemory
import dev.caulis.ffi.SharedMemoryException
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxBufferParamsV1
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufFeedbackV1
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufV1
import dev.caulis.protocol.client.wayland.WlBuffer
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuDevices
import dev.caulis.render.Renderer
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The outer compositor's linux-dmabuf: lets the nested window hand over frames that stay
 * on the GPU instead of copying every pixel through shared memory.
 *
 * [connect] finds out which memory layouts (modifiers) the outer compositor accepts for
 * XRGB8888 on our GPU, and sends one test buffer. It returns null whenever something does
 * not fit (no linux-dmabuf, a different GPU, the test buffer refused); the window then
 * simply keeps using shared memory.
 */
internal class HostDmabuf private constructor(
    private val dmabuf: ZwpLinuxDmabufV1,
    private val renderer: Renderer,
    /** Memory layouts both GPUs understand; GBM picks the best one. */
    val modifiers: List<Long>,
) {

    /** A GPU buffer to draw into, plus the outer compositor's handle for it. */
    fun createBuffer(width: Int, height: Int): Pair<GpuBuffer, WlBuffer> {
        val buffer = renderer.createBuffer(width, height, FORMAT, modifiers, scanout = false)
        try {
            val params = dmabuf.createParams()
            buffer.export().use { exported -> addPlanes(params, exported) }
            // Same format and layouts as the accepted test buffer, so no refusal is expected.
            val wlBuffer = params.createImmed(width, height, FORMAT, 0)
            params.destroy()
            return buffer to wlBuffer
        } catch (e: Exception) {
            buffer.close()
            throw e
        }
    }

    companion object {
        private val FORMAT = Dmabuf.XRGB8888

        /** Call before the connection is attached to an event loop (it waits for answers). */
        fun connect(connection: Connection, dmabuf: ZwpLinuxDmabufV1, renderer: Renderer): HostDmabuf? {
            val ourGpu = renderer.renderNode?.let(GpuDevices::number) ?: return null
            val offered = if (dmabuf.version >= 4) {
                readFeedback(connection, dmabuf, ourGpu)
            } else {
                readModifierEvents(connection, dmabuf)
            } ?: return null

            // The outer compositor must be able to read what our GPU draws.
            val renderable = renderer.dmabufFormats.filter { it.format == FORMAT }.map { it.modifier }.toSet()
            val modifiers = offered.filter { it in renderable && it != Dmabuf.MODIFIER_INVALID }
            if (modifiers.isEmpty()) return null
            val host = HostDmabuf(dmabuf, renderer, modifiers)
            return if (host.testBufferAccepted(connection)) host else null
        }

        // linux-dmabuf v4: a format table in shared memory plus "tranches" per device.
        private fun readFeedback(connection: Connection, dmabuf: ZwpLinuxDmabufV1, ourGpu: Long): List<Long>? {
            var table: List<Pair<Int, Long>> = emptyList()
            var mainDevice = 0L
            var trancheDevice = 0L
            var trancheIndices = ByteArray(0)
            val modifiers = LinkedHashSet<Long>()
            var done = false
            val feedback = dmabuf.getDefaultFeedback()
            feedback.events = object : ZwpLinuxDmabufFeedbackV1.Events {
                override fun formatTable(proxy: ZwpLinuxDmabufFeedbackV1, fd: Fd, size: Int) {
                    table = readFormatTable(fd, size)
                }

                override fun mainDevice(proxy: ZwpLinuxDmabufFeedbackV1, device: ByteArray) {
                    mainDevice = deviceNumber(device)
                }

                override fun trancheTargetDevice(proxy: ZwpLinuxDmabufFeedbackV1, device: ByteArray) {
                    trancheDevice = deviceNumber(device)
                }

                override fun trancheFormats(proxy: ZwpLinuxDmabufFeedbackV1, indices: ByteArray) {
                    trancheIndices += indices
                }

                override fun trancheDone(proxy: ZwpLinuxDmabufFeedbackV1) {
                    // Only layouts meant for our GPU are useful to us.
                    if (GpuDevices.sameGpu(trancheDevice, ourGpu)) {
                        val indices = ByteBuffer.wrap(trancheIndices).order(ByteOrder.nativeOrder())
                        while (indices.remaining() >= 2) {
                            val entry = table.getOrNull(indices.short.toInt() and 0xffff) ?: continue
                            if (entry.first == FORMAT) modifiers += entry.second
                        }
                    }
                    trancheIndices = ByteArray(0)
                }

                override fun done(proxy: ZwpLinuxDmabufFeedbackV1) {
                    done = true
                }
            }
            connection.roundtrip()
            feedback.destroy()
            if (!done || !GpuDevices.sameGpu(mainDevice, ourGpu)) return null
            return modifiers.toList()
        }

        // linux-dmabuf v3: one "modifier" event per format and layout, sent right after binding.
        private fun readModifierEvents(connection: Connection, dmabuf: ZwpLinuxDmabufV1): List<Long>? {
            if (dmabuf.version < 3) return null
            val modifiers = LinkedHashSet<Long>()
            dmabuf.events = object : ZwpLinuxDmabufV1.Events {
                override fun modifier(proxy: ZwpLinuxDmabufV1, format: Int, modifierHi: Int, modifierLo: Int) {
                    if (format == FORMAT) {
                        modifiers += (Integer.toUnsignedLong(modifierHi) shl 32) or Integer.toUnsignedLong(modifierLo)
                    }
                }
            }
            connection.roundtrip()
            // Without feedback we cannot know which GPU the outer compositor uses: plain rows
            // are the one layout every GPU reads.
            return modifiers.filter { it == Dmabuf.MODIFIER_LINEAR }
        }

        // 16 bytes per entry: u32 format, 4 bytes padding, u64 modifier.
        private fun readFormatTable(fd: Fd, size: Int): List<Pair<Int, Long>> = try {
            SharedMemory.map(fd, size.toLong()).use { memory ->
                memory.read { bytes ->
                    (0 until size / 16).map { i ->
                        bytes.get(JAVA_INT_UNALIGNED, i * 16L) to bytes.get(JAVA_LONG_UNALIGNED, i * 16L + 8)
                    }
                }
            }
        } catch (e: SharedMemoryException) {
            emptyList()
        }

        private fun deviceNumber(bytes: ByteArray): Long =
            if (bytes.size == 8) ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).long else -1

        private fun addPlanes(params: ZwpLinuxBufferParamsV1, buffer: Dmabuf) {
            val hi = (buffer.modifier ushr 32).toInt()
            val lo = buffer.modifier.toInt()
            buffer.planes.forEachIndexed { index, plane -> params.add(plane.fd, index, plane.offset, plane.stride, hi, lo) }
        }
    }

    // Sends a small buffer with "create" (which may answer "failed" instead of ending the
    // connection with an error) to make sure the outer compositor really accepts our buffers.
    private fun testBufferAccepted(connection: Connection): Boolean {
        val buffer = try {
            renderer.createBuffer(64, 64, FORMAT, modifiers, scanout = false)
        } catch (e: IllegalStateException) {
            return false // our GPU cannot draw in any of these layouts
        }
        buffer.use {
            var answer: Boolean? = null
            val params = dmabuf.createParams()
            params.events = object : ZwpLinuxBufferParamsV1.Events {
                override fun created(proxy: ZwpLinuxBufferParamsV1, buffer: WlBuffer) {
                    answer = true
                    buffer.destroy()
                }

                override fun failed(proxy: ZwpLinuxBufferParamsV1) {
                    answer = false
                }
            }
            buffer.export().use { exported -> addPlanes(params, exported) }
            params.create(64, 64, FORMAT, 0)
            connection.roundtrip()
            params.destroy()
            return answer == true
        }
    }
}
