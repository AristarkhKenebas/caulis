package dev.caulis.render.gles

import dev.caulis.ffi.c.gles.Gles
import dev.caulis.geometry.Rect
import dev.caulis.render.BlurTone
import dev.caulis.render.MAX_BLUR_PASSES
import dev.caulis.render.blurReach

/**
 * Blurs a part of a framebuffer (dual Kawase, see [Shaders.BLUR_DOWN]): the part is
 * shrunk to half its size a few times and grown back, which spreads every pixel far for
 * little work. The smaller pictures live in [levels], kept from frame to frame.
 */
internal class GlesBlur(private val draw: (program: Program, rect: Rect, targetWidth: Int, targetHeight: Int) -> Unit) : AutoCloseable {

    private val down = Program(Shaders.VERTEX, Shaders.BLUR_DOWN)
    private val up = Program(Shaders.VERTEX, Shaders.BLUR_UP)

    // levels[i] holds the picture shrunk i + 1 times.
    private val levels = ArrayList<GlesTarget>()

    /**
     * Blurs what [target] shows around [area] and draws the result over [area] in it,
     * clipped to [area] with rounded corners. Leaves [target] bound, without scissor.
     */
    fun blur(target: GlesFramebuffer, area: Rect, cornerRadius: Float, size: Int, passes: Int, tone: BlurTone = BlurTone.NONE) {
        val count = passes.coerceIn(1, MAX_BLUR_PASSES)
        val reach = blurReach(size, count)
        val bounds = Rect(0, 0, target.width, target.height)
        val region = Rect(area.x - reach, area.y - reach, area.width + 2 * reach, area.height + 2 * reach).intersect(bounds) ?: return
        prepareLevels(target, count)
        Gles.glDisable(Gles.GL_SCISSOR_TEST())
        Gles.glDisable(Gles.GL_BLEND())

        // Down: the target's region -> level 0 -> level 1 -> ...
        var sourceTexture = target.colorTexture
        var sourceWidth = target.width
        var sourceHeight = target.height
        var sourcePart = region
        for (i in 0 until count) {
            val level = levels[i]
            val part = Rect(0, 0, ceilDiv(region.width, 1 shl (i + 1)), ceilDiv(region.height, 1 shl (i + 1)))
            level.bind()
            pass(down, sourceTexture, sourceWidth, sourceHeight, sourcePart, size)
            draw(down, part, level.width, level.height)
            sourceTexture = level.colorTexture
            sourceWidth = level.width
            sourceHeight = level.height
            sourcePart = part
        }
        // Up: ... -> level 1 -> level 0.
        for (i in count - 2 downTo 0) {
            val level = levels[i]
            val part = Rect(0, 0, ceilDiv(region.width, 1 shl (i + 1)), ceilDiv(region.height, 1 shl (i + 1)))
            level.bind()
            pass(up, sourceTexture, sourceWidth, sourceHeight, sourcePart, size)
            Gles.glUniform1i(up.uniform("clipped"), 0)
            draw(up, part, level.width, level.height)
            sourceTexture = level.colorTexture
            sourceWidth = level.width
            sourceHeight = level.height
            sourcePart = part
        }
        // Last step up, straight into the target, only over the area.
        target.bind()
        Gles.glEnable(Gles.GL_BLEND())
        pass(up, sourceTexture, sourceWidth, sourceHeight, sourcePart, size)
        // Which part of the level-0 picture the area is.
        val scaleX = sourcePart.width.toFloat() / region.width
        val scaleY = sourcePart.height.toFloat() / region.height
        Gles.glUniform4f(
            up.uniform("source"),
            (area.x - region.x) * scaleX / sourceWidth, (area.y - region.y) * scaleY / sourceHeight,
            area.width * scaleX / sourceWidth, area.height * scaleY / sourceHeight,
        )
        Gles.glUniform1i(up.uniform("clipped"), 1)
        Gles.glUniform4f(up.uniform("clip"), area.x.toFloat(), area.y.toFloat(), area.width.toFloat(), area.height.toFloat())
        Gles.glUniform1f(up.uniform("radius"), cornerRadius)
        Gles.glUniform4f(up.uniform("tone"), tone.noise, tone.contrast, tone.brightness, tone.vibrancy)
        draw(up, area, target.width, target.height)
    }

    // Sets up [program] to read [part] of a texture of the given size.
    private fun pass(program: Program, texture: Int, width: Int, height: Int, part: Rect, size: Int) {
        program.use()
        Gles.glActiveTexture(Gles.GL_TEXTURE0())
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), texture)
        Gles.glUniform1i(program.uniform("image"), 0)
        val u = part.x.toFloat() / width
        val v = part.y.toFloat() / height
        val w = part.width.toFloat() / width
        val h = part.height.toFloat() / height
        Gles.glUniform4f(program.uniform("source"), u, v, w, h)
        // Never read outside the part (half a pixel in, so filtering does not either).
        val hx = 0.5f / width
        val hy = 0.5f / height
        Gles.glUniform4f(program.uniform("bounds"), u + hx, v + hy, u + w - hx, v + h - hy)
        Gles.glUniform2f(program.uniform("halfpixel"), hx, hy)
        Gles.glUniform1f(program.uniform("offset"), size.toFloat())
    }

    // Enough levels, each big enough for the target shrunk that many times.
    private fun prepareLevels(target: GlesFramebuffer, count: Int) {
        for (i in 0 until count) {
            val width = ceilDiv(target.width, 1 shl (i + 1)) + 1
            val height = ceilDiv(target.height, 1 shl (i + 1)) + 1
            val existing = levels.getOrNull(i)
            if (existing != null && existing.width >= width && existing.height >= height) continue
            existing?.close()
            val level = GlesTarget(width, height)
            if (i < levels.size) levels[i] = level else levels += level
        }
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    override fun close() {
        for (level in levels) level.close()
        levels.clear()
        down.close()
        up.close()
    }
}
