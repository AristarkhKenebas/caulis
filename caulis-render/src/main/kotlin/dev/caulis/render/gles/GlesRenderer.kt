package dev.caulis.render.gles

import dev.caulis.ffi.Fd
import dev.caulis.ffi.c.gles.Gles
import dev.caulis.geometry.Rect
import dev.caulis.render.Dmabuf
import dev.caulis.render.Color
import dev.caulis.render.DmabufFormat
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * The OpenGL ES 3 [Renderer]: draws rectangles with a colour or a texture, using one
 * small shader each. Create it with [create] on the event loop thread.
 */
class GlesRenderer private constructor(private val egl: EglContext) : Renderer {

    override val description: String get() = "OpenGL ES 3 on ${egl.renderer}"

    override val renderNode: java.nio.file.Path? get() = egl.renderNode

    private val textured = Program(Shaders.VERTEX, Shaders.TEXTURE)
    private val solid = Program(Shaders.VERTEX, Shaders.COLOR)
    private val rounded = Program(Shaders.VERTEX, Shaders.ROUNDED)
    private val shadow = Program(Shaders.VERTEX, Shaders.SHADOW)
    private val vertexArray = Gl.generate(Gles::glGenVertexArrays)
    private val vertexBuffer = Gl.generate(Gles::glGenBuffers)

    private var targetWidth = 1f
    private var targetHeight = 1f
    private var target: GlesFramebuffer? = null
    private var clip: Rect? = null

    init {
        // One unit square as a triangle strip; the vertex shader moves and scales it.
        Gles.glBindVertexArray(vertexArray)
        Gles.glBindBuffer(Gles.GL_ARRAY_BUFFER(), vertexBuffer)
        Arena.ofConfined().use { arena ->
            val square = arena.allocateFrom(JAVA_FLOAT, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            Gles.glBufferData(Gles.GL_ARRAY_BUFFER(), square.byteSize(), square, Gles.GL_STATIC_DRAW())
        }
        Gles.glVertexAttribPointer(0, 2, Gles.GL_FLOAT(), Gles.GL_FALSE().toByte(), 0, java.lang.foreign.MemorySegment.NULL)
        Gles.glEnableVertexAttribArray(0)
        Gl.check("creating the quad renderer")
    }

    private val blur = GlesBlur { program, rect, width, height ->
        Gles.glUniform4f(program.uniform("rect"), rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat())
        Gles.glUniform2f(program.uniform("target"), width.toFloat(), height.toFloat())
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    private val images = DmabufImages(egl)

    override fun createTexture(width: Int, height: Int): GpuTexture = GlesTexture(width, height)

    override fun createTarget(width: Int, height: Int): GpuTarget = GlesTarget(width, height)

    override val dmabufFormats: List<DmabufFormat> by lazy {
        val functions = egl.extensionFunctions
        functions.dmabufFormats(egl.display).flatMap { format ->
            // MODIFIER_INVALID = "implicit layout": what clients without modifier support use.
            (functions.dmabufModifiers(egl.display, format) + Dmabuf.MODIFIER_INVALID).distinct()
                .map { DmabufFormat(format, it) }
        }
    }

    override fun importDmabuf(buffer: Dmabuf): GpuTexture? {
        val image = images.createImage(buffer) ?: return null
        return images.texture(image, buffer.width, buffer.height)
    }

    override fun importDmabufTarget(buffer: Dmabuf): GpuTarget? {
        val image = images.createImage(buffer) ?: return null
        val texture = images.texture(image, buffer.width, buffer.height)
        return try {
            GlesImportedTarget(buffer.width, buffer.height, texture)
        } catch (e: IllegalStateException) {
            texture.close() // the GPU cannot draw into this format
            null
        }
    }

    override fun copy(source: GpuTarget, from: Rect, target: GpuTarget) {
        source as GlesFramebuffer
        target as GlesFramebuffer
        Gles.glDisable(Gles.GL_SCISSOR_TEST())
        Gles.glBindFramebuffer(Gles.GL_READ_FRAMEBUFFER(), source.framebuffer)
        Gles.glBindFramebuffer(Gles.GL_DRAW_FRAMEBUFFER(), target.framebuffer)
        val filter = if (from.width == target.width && from.height == target.height) Gles.GL_NEAREST() else Gles.GL_LINEAR()
        Gles.glBlitFramebuffer(from.x, from.y, from.right, from.bottom, 0, 0, target.width, target.height, Gles.GL_COLOR_BUFFER_BIT(), filter)
        Gles.glBindFramebuffer(Gles.GL_FRAMEBUFFER(), 0)
        Gl.check("copying a picture")
    }

    override fun finish() = Gles.glFinish()

    override fun createBuffer(width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GpuBuffer =
        GlesBuffer.create(egl, images, width, height, format, modifiers, scanout)

    override fun begin(target: GpuTarget, red: Float, green: Float, blue: Float, clip: Rect?, alpha: Float) {
        target as GlesFramebuffer
        this.target = target
        this.clip = clip
        bindTarget(target, clip)
        Gles.glClearColor(red * alpha, green * alpha, blue * alpha, alpha)
        Gles.glClear(Gles.GL_COLOR_BUFFER_BIT())
    }

    private fun bindTarget(target: GlesFramebuffer, clip: Rect?) {
        target.bind()
        targetWidth = target.width.toFloat()
        targetHeight = target.height.toFloat()
        // Pixel row y is framebuffer row y (see Shaders.VERTEX), so the clip needs no flipping.
        if (clip != null) {
            Gles.glEnable(Gles.GL_SCISSOR_TEST())
            Gles.glScissor(clip.x, clip.y, clip.width, clip.height)
        } else {
            Gles.glDisable(Gles.GL_SCISSOR_TEST())
        }
        // Wayland pixels have premultiplied alpha.
        Gles.glEnable(Gles.GL_BLEND())
        Gles.glBlendFunc(Gles.GL_ONE(), Gles.GL_ONE_MINUS_SRC_ALPHA())
        Gles.glBindVertexArray(vertexArray)
    }

    override fun drawTexture(
        texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, opaque: Boolean,
        alpha: Float, clip: Rect?, cornerRadius: Float, source: FloatArray?,
    ) {
        texture as GlTexture
        textured.use()
        setRect(textured, x, y, width, height)
        if (source != null) {
            Gles.glUniform4f(textured.uniform("source"), source[0], source[1], source[2], source[3])
        } else {
            Gles.glUniform4f(textured.uniform("source"), 0f, 0f, 1f, 1f)
        }
        Gles.glActiveTexture(Gles.GL_TEXTURE0())
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), texture.id)
        Gles.glUniform1i(textured.uniform("image"), 0)
        Gles.glUniform1f(textured.uniform("alpha"), alpha)
        Gles.glUniform1i(textured.uniform("opaque"), if (opaque) 1 else 0)
        Gles.glUniform1i(textured.uniform("clipped"), if (clip != null) 1 else 0)
        if (clip != null) {
            setBox(textured, "clip", clip)
            Gles.glUniform1f(textured.uniform("radius"), radiusFor(clip, cornerRadius))
        }
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawColor(x: Int, y: Int, width: Int, height: Int, red: Float, green: Float, blue: Float, alpha: Float) {
        solid.use()
        setRect(solid, x, y, width, height)
        Gles.glUniform4f(solid.uniform("color"), red * alpha, green * alpha, blue * alpha, alpha)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawRoundedRect(box: Rect, cornerRadius: Float, color: Color, borderWidth: Float) {
        if (box.isEmpty) return
        rounded.use()
        setRect(rounded, box.x, box.y, box.width, box.height)
        setColor(rounded, color)
        setBox(rounded, "outer", box)
        Gles.glUniform1f(rounded.uniform("radius"), radiusFor(box, cornerRadius))
        Gles.glUniform1f(rounded.uniform("border"), borderWidth)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawShadow(box: Rect, cornerRadius: Float, softness: Float, color: Color, cutOut: Rect?, cutOutRadius: Float) {
        if (box.isEmpty) return
        val reach = Math.ceil(softness.toDouble()).toInt().coerceAtLeast(1)
        shadow.use()
        setRect(shadow, box.x - reach, box.y - reach, box.width + 2 * reach, box.height + 2 * reach)
        setColor(shadow, color)
        setBox(shadow, "shape", box)
        Gles.glUniform1f(shadow.uniform("radius"), radiusFor(box, cornerRadius))
        // The blur fades out at about three sigmas.
        Gles.glUniform1f(shadow.uniform("sigma"), (softness / 3f).coerceAtLeast(0.5f))
        Gles.glUniform1i(shadow.uniform("cut"), if (cutOut != null) 1 else 0)
        if (cutOut != null) {
            setBox(shadow, "cutOut", cutOut)
            Gles.glUniform1f(shadow.uniform("cutRadius"), radiusFor(cutOut, cutOutRadius))
        }
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawBlur(area: Rect, cornerRadius: Float, size: Int, passes: Int) {
        val target = target ?: return
        if (area.isEmpty || size <= 0) return
        blur.blur(target, area, radiusFor(area, cornerRadius), size, passes)
        bindTarget(target, clip)
    }

    override fun end() {
        Gles.glFlush()
    }

    private fun setRect(program: Program, x: Int, y: Int, width: Int, height: Int) {
        Gles.glUniform4f(program.uniform("rect"), x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
        Gles.glUniform2f(program.uniform("target"), targetWidth, targetHeight)
    }

    private fun setBox(program: Program, name: String, box: Rect) =
        Gles.glUniform4f(program.uniform(name), box.x.toFloat(), box.y.toFloat(), box.width.toFloat(), box.height.toFloat())

    // Premultiplied, as everything we draw.
    private fun setColor(program: Program, color: Color) =
        Gles.glUniform4f(program.uniform("color"), color.red * color.alpha, color.green * color.alpha, color.blue * color.alpha, color.alpha)

    // Corners cannot be rounder than half the box.
    private fun radiusFor(box: Rect, radius: Float) = radius.coerceIn(0f, minOf(box.width, box.height) / 2f)

    override fun close() {
        textured.close()
        solid.close()
        rounded.close()
        shadow.close()
        blur.close()
        Gl.delete(vertexBuffer, Gles::glDeleteBuffers)
        Gl.delete(vertexArray, Gles::glDeleteVertexArrays)
        egl.close()
    }

    companion object {
        /** Opens the GPU (the first render node, or [renderNode]) and prepares drawing. */
        fun create(renderNode: java.nio.file.Path = EglContext.findRenderNode()): GlesRenderer =
            GlesRenderer(EglContext.create(renderNode))

        /**
         * A renderer on an already open GPU device, e.g. the card node the display backend
         * got from libseat (then its buffers can be shown on screen). [device] is not closed.
         */
        fun createOn(device: Fd): GlesRenderer = GlesRenderer(EglContext.createOn(device.value, owned = null))
    }
}

/** A compiled pair of shaders. */
internal class Program(vertexSource: String, fragmentSource: String) : AutoCloseable {

    private val id: Int = Gles.glCreateProgram()
    private val uniforms = HashMap<String, Int>()

    init {
        val vertex = compile(Gles.GL_VERTEX_SHADER(), vertexSource)
        val fragment = compile(Gles.GL_FRAGMENT_SHADER(), fragmentSource)
        Gles.glAttachShader(id, vertex)
        Gles.glAttachShader(id, fragment)
        Gles.glLinkProgram(id)
        Gles.glDeleteShader(vertex)
        Gles.glDeleteShader(fragment)
        check(status(Gles::glGetProgramiv, Gles.GL_LINK_STATUS()) == Gles.GL_TRUE()) {
            "Shader program failed to link: ${log(Gles::glGetProgramInfoLog, id)}"
        }
    }

    fun use() = Gles.glUseProgram(id)

    fun uniform(name: String): Int = uniforms.getOrPut(name) {
        Arena.ofConfined().use { arena -> Gles.glGetUniformLocation(id, arena.allocateFrom(name)) }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = Gles.glCreateShader(type)
        Arena.ofConfined().use { arena ->
            val text = arena.allocateFrom(java.lang.foreign.ValueLayout.ADDRESS, arena.allocateFrom(source.trimIndent()))
            Gles.glShaderSource(shader, 1, text, java.lang.foreign.MemorySegment.NULL)
        }
        Gles.glCompileShader(shader)
        val ok = Arena.ofConfined().use { arena ->
            val value = arena.allocate(JAVA_INT)
            Gles.glGetShaderiv(shader, Gles.GL_COMPILE_STATUS(), value)
            value.get(JAVA_INT, 0)
        }
        check(ok == Gles.GL_TRUE()) { "Shader failed to compile: ${log(Gles::glGetShaderInfoLog, shader)}" }
        return shader
    }

    private fun status(get: (Int, Int, java.lang.foreign.MemorySegment) -> Unit, name: Int): Int =
        Arena.ofConfined().use { arena ->
            val value = arena.allocate(JAVA_INT)
            get(id, name, value)
            value.get(JAVA_INT, 0)
        }

    private fun log(get: (Int, Int, java.lang.foreign.MemorySegment, java.lang.foreign.MemorySegment) -> Unit, of: Int): String =
        Arena.ofConfined().use { arena ->
            val text = arena.allocate(4096)
            get(of, 4096, java.lang.foreign.MemorySegment.NULL, text)
            text.getString(0)
        }

    override fun close() = Gles.glDeleteProgram(id)
}
