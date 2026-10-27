package dev.caulis.render.gles

import dev.caulis.ffi.c.egl.Egl
import dev.caulis.ffi.c.gles.Gles
import dev.caulis.render.Dmabuf
import dev.caulis.render.GpuTexture
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG

/** Turns dma-bufs into EGL images and textures. */
internal class DmabufImages(private val egl: EglContext) {

    /** An EGL image of [buffer], or null if the driver refuses it (unsupported format/layout). */
    fun createImage(buffer: Dmabuf): MemorySegment? {
        val attributes = ArrayList<Long>()
        fun add(name: Int, value: Long) {
            attributes += name.toLong()
            attributes += value
        }
        add(Egl.EGL_WIDTH(), buffer.width.toLong())
        add(Egl.EGL_HEIGHT(), buffer.height.toLong())
        add(Egl.EGL_LINUX_DRM_FOURCC_EXT(), Integer.toUnsignedLong(buffer.format))
        for ((index, plane) in buffer.planes.withIndex()) {
            val names = PLANE_ATTRIBUTES[index]
            add(names[0], plane.fd.value.toLong())
            add(names[1], Integer.toUnsignedLong(plane.offset))
            add(names[2], Integer.toUnsignedLong(plane.stride))
            if (buffer.modifier != Dmabuf.MODIFIER_INVALID) {
                add(names[3], buffer.modifier and 0xFFFFFFFFL)
                add(names[4], buffer.modifier ushr 32)
            }
        }
        add(Egl.EGL_IMAGE_PRESERVED_KHR(), Egl.EGL_TRUE().toLong())
        attributes += Egl.EGL_NONE().toLong()

        val image = Arena.ofConfined().use { arena ->
            // EGL 1.5 eglCreateImage takes EGLAttrib values (64-bit).
            val list = arena.allocateFrom(JAVA_LONG, *attributes.toLongArray())
            Egl.eglCreateImage(egl.display, MemorySegment.NULL, Egl.EGL_LINUX_DMA_BUF_EXT(), MemorySegment.NULL, list)
        }
        return if (image == MemorySegment.NULL) null else image
    }

    fun destroyImage(image: MemorySegment) {
        Egl.eglDestroyImage(egl.display, image)
    }

    /** A texture showing [image] (no copy: it reads the buffer itself). */
    fun texture(image: MemorySegment, width: Int, height: Int): ImageTexture {
        Gl.drain("something drawn before a dma-buf texture")
        val id = Gl.generate(Gles::glGenTextures)
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), id)
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_MIN_FILTER(), Gles.GL_LINEAR())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_MAG_FILTER(), Gles.GL_LINEAR())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_WRAP_S(), Gles.GL_CLAMP_TO_EDGE())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_WRAP_T(), Gles.GL_CLAMP_TO_EDGE())
        try {
            egl.extensionFunctions.imageTargetTexture2D(Gles.GL_TEXTURE_2D(), image)
            Gl.check("binding a dma-buf to a texture")
        } catch (e: Exception) {
            Gl.delete(id, Gles::glDeleteTextures) // (the image is the caller's)
            throw e
        }
        return ImageTexture(id, width, height, image, this)
    }

    private companion object {
        // Per plane: fd, offset, pitch, modifier low, modifier high.
        val PLANE_ATTRIBUTES = listOf(
            intArrayOf(Egl.EGL_DMA_BUF_PLANE0_FD_EXT(), Egl.EGL_DMA_BUF_PLANE0_OFFSET_EXT(), Egl.EGL_DMA_BUF_PLANE0_PITCH_EXT(),
                Egl.EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT(), Egl.EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT()),
            intArrayOf(Egl.EGL_DMA_BUF_PLANE1_FD_EXT(), Egl.EGL_DMA_BUF_PLANE1_OFFSET_EXT(), Egl.EGL_DMA_BUF_PLANE1_PITCH_EXT(),
                Egl.EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT(), Egl.EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT()),
            intArrayOf(Egl.EGL_DMA_BUF_PLANE2_FD_EXT(), Egl.EGL_DMA_BUF_PLANE2_OFFSET_EXT(), Egl.EGL_DMA_BUF_PLANE2_PITCH_EXT(),
                Egl.EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT(), Egl.EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT()),
            intArrayOf(Egl.EGL_DMA_BUF_PLANE3_FD_EXT(), Egl.EGL_DMA_BUF_PLANE3_OFFSET_EXT(), Egl.EGL_DMA_BUF_PLANE3_PITCH_EXT(),
                Egl.EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT(), Egl.EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT()),
        )
    }
}

/** A texture that shows a dma-buf directly (cannot be uploaded to). */
internal class ImageTexture(
    override val id: Int,
    override val width: Int,
    override val height: Int,
    private val image: MemorySegment,
    private val images: DmabufImages,
) : GlTexture {

    override fun upload(pixels: MemorySegment, firstRow: Int, rowCount: Int) =
        throw UnsupportedOperationException("a dma-buf texture shows the buffer itself; it cannot be uploaded to")

    override fun close() {
        Gl.delete(id, Gles::glDeleteTextures)
        images.destroyImage(image)
    }
}

/** Any texture the GLES renderer can draw. */
@OptIn(dev.caulis.log.InternalCaulisApi::class)
internal interface GlTexture : GpuTexture {
    val id: Int
}
