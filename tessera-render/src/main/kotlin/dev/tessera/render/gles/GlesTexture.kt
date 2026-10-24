package dev.tessera.render.gles

import dev.tessera.render.GpuTexture

import dev.tessera.ffi.c.gles.Gles
import java.lang.foreign.MemorySegment

/**
 * A picture on the GPU, [width]x[height] pixels, stored as BGRA bytes (the byte order of
 * Wayland's ARGB8888/XRGB8888 formats on little-endian machines).
 */
internal class GlesTexture(override val width: Int, override val height: Int) : GlTexture {

    override val id: Int = Gl.generate(Gles::glGenTextures)

    init {
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), id)
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_MIN_FILTER(), Gles.GL_LINEAR())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_MAG_FILTER(), Gles.GL_LINEAR())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_WRAP_S(), Gles.GL_CLAMP_TO_EDGE())
        Gles.glTexParameteri(Gles.GL_TEXTURE_2D(), Gles.GL_TEXTURE_WRAP_T(), Gles.GL_CLAMP_TO_EDGE())
        Gles.glTexImage2D(
            Gles.GL_TEXTURE_2D(), 0, Gles.GL_BGRA_EXT(), width, height, 0,
            Gles.GL_BGRA_EXT(), Gles.GL_UNSIGNED_BYTE(), MemorySegment.NULL,
        )
        Gl.check("creating a ${width}x$height texture")
    }

    override fun upload(pixels: MemorySegment, firstRow: Int, rowCount: Int) {
        require(firstRow >= 0 && rowCount > 0 && firstRow + rowCount <= height) { "rows $firstRow+$rowCount outside $height" }
        require(pixels.byteSize() >= rowCount.toLong() * width * 4) { "not enough pixel data" }
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), id)
        Gles.glPixelStorei(Gles.GL_UNPACK_ALIGNMENT(), 4)
        Gles.glTexSubImage2D(
            Gles.GL_TEXTURE_2D(), 0, 0, firstRow, width, rowCount,
            Gles.GL_BGRA_EXT(), Gles.GL_UNSIGNED_BYTE(), pixels,
        )
        Gl.check("uploading a texture")
    }

    override fun close() = Gl.delete(id, Gles::glDeleteTextures)
}
