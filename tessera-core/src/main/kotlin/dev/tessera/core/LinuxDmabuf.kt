package dev.tessera.core

import dev.tessera.ffi.Fd
import dev.tessera.ffi.SharedMemory
import dev.tessera.protocol.Display
import dev.tessera.protocol.linuxdmabufv1.ZwpLinuxBufferParamsV1
import dev.tessera.protocol.linuxdmabufv1.ZwpLinuxDmabufFeedbackV1
import dev.tessera.protocol.linuxdmabufv1.ZwpLinuxDmabufV1
import dev.tessera.protocol.wayland.WlBuffer
import dev.tessera.protocol.wayland.WlSurface
import dev.tessera.render.Dmabuf
import dev.tessera.render.GpuTexture
import dev.tessera.render.Renderer
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * zwp_linux_dmabuf_v1: programs that draw on the GPU (browsers, games, GTK and Qt with
 * OpenGL/Vulkan) hand us their GPU buffers directly, without copying pixels.
 *
 * Clients learn which formats work from a table ([formatTable]) and which GPU to use
 * (the renderer's render node). Every buffer they create is checked (planes, sizes) and tried on the GPU
 * before it is accepted.
 */
class LinuxDmabuf(display: Display, private val renderer: Renderer) {

    private val renderNode: Path = requireNotNull(renderer.renderNode) {
        "The GPU driver has no render node, so clients cannot share GPU buffers"
    }

    private val formats = renderer.dmabufFormats

    // The table clients read: 16 bytes per entry (u32 format, u32 padding, u64 modifier).
    private val formatTable: SharedMemory = SharedMemory.create("tessera-dmabuf-formats", formats.size * 16L).also { table ->
        table.write { memory ->
            val bytes = ByteBuffer.allocate(formats.size * 16).order(ByteOrder.nativeOrder())
            for (entry in formats) {
                bytes.putInt(entry.format)
                bytes.putInt(0)
                bytes.putLong(entry.modifier)
            }
            MemorySegment.copy(MemorySegment.ofArray(bytes.array()), 0, memory, 0, bytes.capacity().toLong())
        }
    }

    // The GPU's device number (dev_t), as the 8 bytes the protocol wants.
    private val mainDevice: ByteArray = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        .putLong(Files.getAttribute(renderNode, "unix:rdev") as Long).array()

    init {
        display.addGlobal(ZwpLinuxDmabufV1, VERSION) { dmabuf ->
            dmabuf.requests = requests
            // Clients older than version 4 learn the formats from events instead of a table.
            if (dmabuf.version < 4) {
                for (entry in formats) {
                    if (dmabuf.version >= 3) {
                        dmabuf.sendModifier(entry.format, (entry.modifier ushr 32).toInt(), entry.modifier.toInt())
                    } else {
                        dmabuf.sendFormat(entry.format)
                    }
                }
            }
        }
    }

    private val requests = object : ZwpLinuxDmabufV1.Requests {
        override fun destroy(resource: ZwpLinuxDmabufV1) {}

        override fun createParams(resource: ZwpLinuxDmabufV1, paramsId: ZwpLinuxBufferParamsV1) {
            BufferParams(paramsId, renderer)
        }

        override fun getDefaultFeedback(resource: ZwpLinuxDmabufV1, id: ZwpLinuxDmabufFeedbackV1) = sendFeedback(id)

        override fun getSurfaceFeedback(resource: ZwpLinuxDmabufV1, id: ZwpLinuxDmabufFeedbackV1, surface: WlSurface) =
            sendFeedback(id)
    }

    // One tranche: every format, for our only GPU.
    private fun sendFeedback(feedback: ZwpLinuxDmabufFeedbackV1) {
        feedback.requests = object : ZwpLinuxDmabufFeedbackV1.Requests {
            override fun destroy(resource: ZwpLinuxDmabufFeedbackV1) {}
        }
        feedback.sendFormatTable(formatTable.fd, formatTable.size.toInt())
        feedback.sendMainDevice(mainDevice)
        feedback.sendTrancheTargetDevice(mainDevice)
        feedback.sendTrancheFlags(0)
        val indices = ByteBuffer.allocate(formats.size * 2).order(ByteOrder.nativeOrder())
        formats.indices.forEach { indices.putShort(it.toShort()) }
        feedback.sendTrancheFormats(indices.array())
        feedback.sendTrancheDone()
        feedback.sendDone()
    }

    companion object {
        const val VERSION = 4
    }
}

/** The planes a client collects (zwp_linux_buffer_params_v1) before creating a buffer. */
private class BufferParams(private val resource: ZwpLinuxBufferParamsV1, private val renderer: Renderer) {

    private class PlaneInfo(val fd: Fd, val offset: Int, val stride: Int, val modifier: Long)

    private val planes = HashMap<Int, PlaneInfo>()
    private var used = false

    init {
        resource.requests = object : ZwpLinuxBufferParamsV1.Requests {
            override fun destroy(resource: ZwpLinuxBufferParamsV1) {}

            override fun add(resource: ZwpLinuxBufferParamsV1, fd: Fd, planeIdx: Int, offset: Int, stride: Int, modifierHi: Int, modifierLo: Int) {
                val error = when {
                    used -> ZwpLinuxBufferParamsV1.Error.ALREADY_USED to "these parameters were already used"
                    planeIdx !in 0..3 -> ZwpLinuxBufferParamsV1.Error.PLANE_IDX to "plane index $planeIdx out of range"
                    planeIdx in planes -> ZwpLinuxBufferParamsV1.Error.PLANE_SET to "plane $planeIdx was already set"
                    else -> null
                }
                if (error != null) {
                    fd.close()
                    resource.postError(error.first, error.second)
                    return
                }
                val modifier = (Integer.toUnsignedLong(modifierHi) shl 32) or Integer.toUnsignedLong(modifierLo)
                planes[planeIdx] = PlaneInfo(fd, offset, stride, modifier)
            }

            override fun create(resource: ZwpLinuxBufferParamsV1, width: Int, height: Int, format: Int, flags: Int) {
                val buffer = build(width, height, format, flags) ?: return
                val texture = renderer.importDmabuf(buffer)
                if (texture == null) {
                    buffer.close()
                    resource.sendFailed()
                    return
                }
                val wlBuffer = resource.client.createServerResource(WlBuffer, 1)
                DmabufBuffer(wlBuffer, buffer, texture)
                resource.sendCreated(wlBuffer)
            }

            override fun createImmed(resource: ZwpLinuxBufferParamsV1, bufferId: WlBuffer, width: Int, height: Int, format: Int, flags: Int) {
                val buffer = build(width, height, format, flags) ?: return
                val texture = renderer.importDmabuf(buffer)
                if (texture == null) {
                    buffer.close()
                    resource.postError(ZwpLinuxBufferParamsV1.Error.INVALID_WL_BUFFER, "the GPU cannot use this buffer")
                    return
                }
                DmabufBuffer(bufferId, buffer, texture)
            }

            override fun setSamplingDevice(resource: ZwpLinuxBufferParamsV1, device: ByteArray) {}
        }
        resource.onDestroy { if (!used) planes.values.forEach { it.fd.close() } }
    }

    // Checks everything and turns the planes into a Dmabuf (which then owns the descriptors).
    private fun build(width: Int, height: Int, format: Int, flags: Int): Dmabuf? {
        fun fail(code: Int, message: String): Dmabuf? {
            resource.postError(code, message)
            return null
        }
        if (used) return fail(ZwpLinuxBufferParamsV1.Error.ALREADY_USED, "these parameters were already used")
        used = true
        if (planes.isEmpty() || (0 until planes.size).any { it !in planes }) {
            planes.values.forEach { it.fd.close() }
            return fail(ZwpLinuxBufferParamsV1.Error.INCOMPLETE, "planes must be numbered 0, 1, ... without gaps")
        }
        val ordered = (0 until planes.size).map { planes.getValue(it) }
        val modifier = ordered.first().modifier
        val problem = when {
            width <= 0 || height <= 0 -> ZwpLinuxBufferParamsV1.Error.INVALID_DIMENSIONS to "size ${width}x$height"
            flags != 0 -> ZwpLinuxBufferParamsV1.Error.INVALID_FORMAT to "flags $flags are not supported"
            ordered.any { it.modifier != modifier } -> ZwpLinuxBufferParamsV1.Error.INVALID_FORMAT to "planes have different modifiers"
            else -> ordered.withIndex().firstNotNullOfOrNull { (index, plane) -> outOfBounds(plane, index, height) }
        }
        if (problem != null) {
            ordered.forEach { it.fd.close() }
            return fail(problem.first, problem.second)
        }
        return Dmabuf(width, height, format, modifier, ordered.map { Dmabuf.Plane(it.fd, it.offset, it.stride) })
    }

    // The plane must lie inside its memory (computed in Long, so it cannot wrap around).
    // Only plain row-by-row buffers (linear, first plane) have a known full size; for tiled
    // ones the GPU driver and the kernel check the rest when the buffer is imported.
    private fun outOfBounds(plane: PlaneInfo, index: Int, height: Int): Pair<Int, String>? {
        val size = plane.fd.sizeOrNull() ?: return null // size unknown: the GPU import decides
        val offset = Integer.toUnsignedLong(plane.offset)
        val stride = Integer.toUnsignedLong(plane.stride)
        val rows = if (index == 0 && plane.modifier == Dmabuf.MODIFIER_LINEAR) height.toLong() else 1L
        val end = offset + stride * rows
        return if (end > size) {
            ZwpLinuxBufferParamsV1.Error.OUT_OF_BOUNDS to "plane $index ends at byte $end, the buffer has $size"
        } else {
            null
        }
    }
}

/**
 * A client's GPU buffer, already imported as a texture.
 *
 * The texture is shared by every surface that shows the buffer. It stays alive while any
 * surface shows it, even if the client destroys the buffer meanwhile; the client gets
 * the buffer back (wl_buffer.release) once no surface shows it any more.
 */
class DmabufBuffer internal constructor(
    val resource: WlBuffer,
    private val dmabuf: Dmabuf,
    val texture: GpuTexture,
) : ClientBuffer {

    override val width: Int get() = dmabuf.width
    override val height: Int get() = dmabuf.height

    /** True if the format has no alpha channel (faster to draw). */
    val opaque: Boolean get() = dmabuf.format == Dmabuf.XRGB8888 || dmabuf.format == Dmabuf.fourcc("XB24")

    private var shownBy = 0
    private var closed = false

    init {
        resource.data = this
        resource.requests = object : WlBuffer.Requests {
            override fun destroy(resource: WlBuffer) {}
        }
        resource.onDestroy { closeIfUnused() }
    }

    internal fun startShowing() {
        shownBy++
    }

    internal fun stopShowing() {
        if (--shownBy > 0) return
        if (resource.isAlive) resource.sendRelease() else closeIfUnused()
    }

    private fun closeIfUnused() {
        if (shownBy > 0 || closed) return
        closed = true
        texture.close()
        dmabuf.close()
    }
}
