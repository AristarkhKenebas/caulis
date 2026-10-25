package dev.caulis.render.gles

import dev.caulis.ffi.Fd
import dev.caulis.ffi.c.gbm.Gbm
import dev.caulis.ffi.c.gbm.gbm_bo_handle
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuBuffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * A GBM buffer object we draw into through an EGL image. Its memory can be scanned out
 * by the display or exported as a dma-buf.
 */
internal class GlesBuffer private constructor(
    width: Int,
    height: Int,
    private val bo: MemorySegment,
    private val images: DmabufImages,
) : GlesFramebuffer(width, height), GpuBuffer {

    override val format: Int = Gbm.gbm_bo_get_format(bo)
    override val modifier: Long = Gbm.gbm_bo_get_modifier(bo)
    override val planeCount: Int = Gbm.gbm_bo_get_plane_count(bo)

    private val image: ImageTexture

    init {
        val exported = export()
        try {
            val image = images.createImage(exported) ?: error("the GPU cannot draw into its own buffer")
            this.image = images.texture(image, width, height)
        } finally {
            exported.close() // the EGL image keeps its own reference to the memory
        }
        attach(this.image.id)
    }

    override fun handle(plane: Int): Int = Arena.ofConfined().use { arena ->
        gbm_bo_handle.u32(Gbm.gbm_bo_get_handle_for_plane(arena, bo, plane))
    }

    override fun stride(plane: Int): Int = Gbm.gbm_bo_get_stride_for_plane(bo, plane)

    override fun offset(plane: Int): Int = Gbm.gbm_bo_get_offset(bo, plane)

    override fun export(): Dmabuf {
        val planes = (0 until planeCount).map { plane ->
            val fd = Gbm.gbm_bo_get_fd_for_plane(bo, plane)
            check(fd >= 0) { "gbm_bo_get_fd_for_plane failed" }
            Dmabuf.Plane(Fd.adopt(fd), offset(plane), stride(plane))
        }
        return Dmabuf(width, height, format, modifier, planes)
    }

    override fun close() {
        super.close()
        this.image.close()
        Gbm.gbm_bo_destroy(bo)
    }

    companion object {
        fun create(egl: EglContext, images: DmabufImages, width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GlesBuffer {
            var flags = Gbm.GBM_BO_USE_RENDERING()
            if (scanout) flags = flags or Gbm.GBM_BO_USE_SCANOUT()
            val explicit = modifiers.filter { it != Dmabuf.MODIFIER_INVALID }
            val bo = Arena.ofConfined().use { arena ->
                if (explicit.isEmpty()) {
                    Gbm.gbm_bo_create(egl.gbmDevice, width, height, format, flags)
                } else {
                    val list = arena.allocateFrom(JAVA_LONG, *explicit.toLongArray())
                    Gbm.gbm_bo_create_with_modifiers2(egl.gbmDevice, width, height, format, list, explicit.size, flags)
                }
            }
            check(bo != MemorySegment.NULL) { "could not allocate a ${width}x$height GPU buffer" }
            return GlesBuffer(width, height, bo, images)
        }
    }
}
