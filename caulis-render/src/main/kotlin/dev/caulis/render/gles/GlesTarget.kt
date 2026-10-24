package dev.caulis.render.gles

import dev.caulis.ffi.c.gles.Gles
import dev.caulis.render.GpuTarget
import java.lang.foreign.MemorySegment

/** Something the GLES renderer can draw into: a framebuffer with a texture attached. */
internal abstract class GlesFramebuffer(final override val width: Int, final override val height: Int) : GpuTarget {

    private var framebuffer = 0

    /** The texture holding the picture (the renderer reads it back for blur). */
    var colorTexture = 0
        private set

    /** Attaches [textureId] as the picture to draw into. Called once by subclasses. */
    protected fun attach(textureId: Int) {
        colorTexture = textureId
        framebuffer = Gl.generate(Gles::glGenFramebuffers)
        Gles.glBindFramebuffer(Gles.GL_FRAMEBUFFER(), framebuffer)
        Gles.glFramebufferTexture2D(Gles.GL_FRAMEBUFFER(), Gles.GL_COLOR_ATTACHMENT0(), Gles.GL_TEXTURE_2D(), textureId, 0)
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

    /** Waits until everything drawn into it is finished on the GPU. */
    fun finish() = Gles.glFinish()

    override fun close() {
        Gl.delete(framebuffer, Gles::glDeleteFramebuffers)
    }
}

/** A target in GPU memory only Caulis uses (e.g. for screenshots or tests). */
internal class GlesTarget(width: Int, height: Int) : GlesFramebuffer(width, height) {

    val texture = GlesTexture(width, height)

    init {
        attach(texture.id)
    }

    override fun close() {
        super.close()
        texture.close()
    }
}
