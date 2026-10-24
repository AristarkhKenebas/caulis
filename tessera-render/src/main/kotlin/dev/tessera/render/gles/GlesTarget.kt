package dev.tessera.render.gles

import dev.tessera.render.GpuTarget

import dev.tessera.ffi.c.gles.Gles
import java.lang.foreign.MemorySegment

/** A texture that can be drawn into (an OpenGL framebuffer), e.g. one whole output frame. */
internal class GlesTarget(override val width: Int, override val height: Int) : GpuTarget {

    val texture = GlesTexture(width, height)
    private val framebuffer: Int = Gl.generate(Gles::glGenFramebuffers)

    init {
        Gles.glBindFramebuffer(Gles.GL_FRAMEBUFFER(), framebuffer)
        Gles.glFramebufferTexture2D(Gles.GL_FRAMEBUFFER(), Gles.GL_COLOR_ATTACHMENT0(), Gles.GL_TEXTURE_2D(), texture.id, 0)
        val status = Gles.glCheckFramebufferStatus(Gles.GL_FRAMEBUFFER())
        check(status == Gles.GL_FRAMEBUFFER_COMPLETE()) { "framebuffer incomplete: 0x${Integer.toHexString(status)}" }
    }

    /** Makes this the target of the following drawing calls. */
    fun bind() {
        Gles.glBindFramebuffer(Gles.GL_FRAMEBUFFER(), framebuffer)
        Gles.glViewport(0, 0, width, height)
    }

    /** Waits until the GPU has finished drawing, then copies the picture. */
    override fun readPixels(destination: MemorySegment, stride: Int) {
        require(stride == width * 4) { "only tightly packed rows are supported" }
        require(destination.byteSize() >= stride.toLong() * height) { "destination too small" }
        bind()
        Gles.glPixelStorei(Gles.GL_PACK_ALIGNMENT(), 4)
        Gles.glReadPixels(0, 0, width, height, Gles.GL_BGRA_EXT(), Gles.GL_UNSIGNED_BYTE(), destination)
        Gl.check("reading pixels")
    }

    override fun close() {
        Gl.delete(framebuffer, Gles::glDeleteFramebuffers)
        texture.close()
    }
}
