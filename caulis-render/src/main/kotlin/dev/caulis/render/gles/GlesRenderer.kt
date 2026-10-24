package dev.caulis.render.gles

import dev.caulis.ffi.Fd
import dev.caulis.render.Dmabuf
import dev.caulis.render.DmabufFormat
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer

import dev.caulis.ffi.c.gles.Gles
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

    private val textured = Program(VERTEX, TEXTURE_FRAGMENT)
    private val solid = Program(VERTEX, COLOR_FRAGMENT)
    private val vertexArray = Gl.generate(Gles::glGenVertexArrays)
    private val vertexBuffer = Gl.generate(Gles::glGenBuffers)

    private var targetWidth = 1f
    private var targetHeight = 1f

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

    override fun createBuffer(width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GpuBuffer =
        GlesBuffer.create(egl, images, width, height, format, modifiers, scanout)

    override fun begin(target: GpuTarget, red: Float, green: Float, blue: Float) {
        target as GlesFramebuffer
        target.bind()
        targetWidth = target.width.toFloat()
        targetHeight = target.height.toFloat()
        Gles.glClearColor(red, green, blue, 1f)
        Gles.glClear(Gles.GL_COLOR_BUFFER_BIT())
        // Wayland pixels have premultiplied alpha.
        Gles.glEnable(Gles.GL_BLEND())
        Gles.glBlendFunc(Gles.GL_ONE(), Gles.GL_ONE_MINUS_SRC_ALPHA())
        Gles.glBindVertexArray(vertexArray)
    }

    override fun drawTexture(texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, opaque: Boolean, alpha: Float) {
        texture as GlTexture
        textured.use()
        setRect(textured, x, y, width, height)
        Gles.glActiveTexture(Gles.GL_TEXTURE0())
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), texture.id)
        Gles.glUniform1i(textured.uniform("image"), 0)
        Gles.glUniform1f(textured.uniform("alpha"), alpha)
        Gles.glUniform1i(textured.uniform("opaque"), if (opaque) 1 else 0)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawColor(x: Int, y: Int, width: Int, height: Int, red: Float, green: Float, blue: Float, alpha: Float) {
        solid.use()
        setRect(solid, x, y, width, height)
        Gles.glUniform4f(solid.uniform("color"), red * alpha, green * alpha, blue * alpha, alpha)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun end() {
        Gles.glFlush()
    }

    private fun setRect(program: Program, x: Int, y: Int, width: Int, height: Int) {
        Gles.glUniform4f(program.uniform("rect"), x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
        Gles.glUniform2f(program.uniform("target"), targetWidth, targetHeight)
    }

    override fun close() {
        textured.close()
        solid.close()
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


        // Pixel rectangle -> OpenGL coordinates. Pixel row 0 goes to framebuffer row 0, so
        // reading the framebuffer back gives the top row first.
        private const val VERTEX = """#version 300 es
            layout(location = 0) in vec2 corner;
            uniform vec4 rect;    // x, y, width, height in pixels
            uniform vec2 target;  // size of the render target in pixels
            out vec2 uv;
            void main() {
                vec2 pixel = rect.xy + corner * rect.zw;
                gl_Position = vec4(pixel / target * 2.0 - 1.0, 0.0, 1.0);
                uv = corner;
            }"""

        private const val TEXTURE_FRAGMENT = """#version 300 es
            precision mediump float;
            in vec2 uv;
            uniform sampler2D image;
            uniform float alpha;
            uniform bool opaque;
            out vec4 color;
            void main() {
                vec4 pixel = texture(image, uv);
                if (opaque) pixel.a = 1.0;
                color = pixel * alpha;
            }"""

        private const val COLOR_FRAGMENT = """#version 300 es
            precision mediump float;
            uniform vec4 color;
            out vec4 fragment;
            void main() { fragment = color; }"""
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
