package dev.caulis.render.gles

import dev.caulis.ffi.Fd
import dev.caulis.ffi.c.gles.Gles
import dev.caulis.geometry.Rect
import dev.caulis.render.Dmabuf
import dev.caulis.render.BlurTone
import dev.caulis.render.Color
import dev.caulis.render.DmabufFormat
import dev.caulis.render.GpuBuffer
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Paint
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
    private val rounded = Program(Shaders.VERTEX, Shaders.rounded(Shaders.PAINT_SOLID))
    private val shadow = Program(Shaders.VERTEX, Shaders.shadow(Shaders.PAINT_SOLID))

    // Rounded boxes and shadows filled with gradients or users' paints, compiled when first
    // needed (null: did not compile, see effectProblems).
    private val painted = programCache()

    private fun paintedProgram(shadowOf: Boolean, paint: Paint): Program? {
        val fill = when (paint) {
            is Paint.Solid -> return if (shadowOf) shadow else rounded
            is Paint.Gradient -> Shaders.PAINT_GRADIENT
            is Paint.Shader -> Shaders.paintCustom(paint.code)
        }
        val source = if (shadowOf) Shaders.shadow(fill) else Shaders.rounded(fill)
        return painted.compiledOnce(source) {
            try {
                Program(Shaders.VERTEX, source)
            } catch (e: IllegalStateException) {
                if (paint is Paint.Shader) effectProblems[paint.code] = e.message ?: "$e"
                null
            }
        }
    }

    // The paint's own values: a colour, a gradient's colours and angle, a shader's time and size.
    private fun setPaint(program: Program, paint: Paint, box: Rect, time: Float, state: dev.caulis.render.PaintState = dev.caulis.render.PaintState.NONE) {
        when (paint) {
            is Paint.Solid -> setColor(program, paint.color)
            is Paint.Gradient -> {
                for ((i, c) in paint.colors.withIndex()) Gles.glUniform4f(program.uniform("colors[$i]"), c.red, c.green, c.blue, c.alpha)
                Gles.glUniform1i(program.uniform("count"), paint.colors.size)
                val degrees = paint.degrees + paint.turnsPerSecond * 360.0 * time
                Gles.glUniform1f(program.uniform("angle"), Math.toRadians(degrees % 360.0).toFloat())
                Gles.glUniform1i(program.uniform("conic"), if (paint.conic) 1 else 0)
            }
            is Paint.Shader -> {
                Gles.glUniform1f(program.uniform("time"), time)
                Gles.glUniform2f(program.uniform("size"), box.width.toFloat(), box.height.toFloat())
                Gles.glUniform1f(program.uniform("focused"), if (state.focused) 1f else 0f)
                Gles.glUniform1f(program.uniform("urgent"), if (state.urgent) 1f else 0f)
                Gles.glUniform1f(program.uniform("grouped"), if (state.grouped) 1f else 0f)
                for ((i, c) in paint.colors.withIndex()) Gles.glUniform4f(program.uniform("colors[$i]"), c.red, c.green, c.blue, c.alpha)
                Gles.glUniform1i(program.uniform("colorCount"), paint.colors.size)
            }
        }
    }
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
            // "External only" layouts need a special shader (video formats, YUV), which we do
            // not have: never offered — a program would show black. (It falls back to another
            // format, e.g. the video decoder hands RGB pictures instead.)
            val all = functions.dmabufModifiersAll(egl.display, format)
            val usable = all.filter { !it.second }.map { it.first }
            // MODIFIER_INVALID = "implicit layout": what clients without modifier support use —
            // only for a format that can be drawn at all (and, without any list, not for YUV).
            val implicit = usable.isNotEmpty() || (all.isEmpty() && !isYuv(format))
            (usable + if (implicit) listOf(Dmabuf.MODIFIER_INVALID) else emptyList()).distinct()
                .map { DmabufFormat(format, it) }
        }
    }

    // Video formats (luma and chroma), which need the external-image shader.
    private fun isYuv(format: Int): Boolean {
        val name = String(CharArray(4) { i -> ((format ushr (8 * i)) and 0xff).toChar() })
        return name in YUV_FORMATS
    }

    override fun importDmabuf(buffer: Dmabuf): GpuTexture? {
        val image = images.createImage(buffer) ?: return null
        return try {
            images.texture(image, buffer.width, buffer.height)
        } catch (e: Exception) {
            images.destroyImage(image) // the GPU cannot read it after all: not importable
            null
        }
    }

    override fun importDmabufTarget(buffer: Dmabuf): GpuTarget? {
        val image = images.createImage(buffer) ?: return null
        val texture = try {
            images.texture(image, buffer.width, buffer.height)
        } catch (e: Exception) {
            images.destroyImage(image)
            return null
        }
        return try {
            GlesImportedTarget(buffer.width, buffer.height, texture)
        } catch (e: IllegalStateException) {
            texture.close() // the GPU cannot draw into this format
            null
        }
    }

    override fun copy(source: GpuTarget, from: Rect, target: GpuTarget) {
        source as? GlesFramebuffer ?: throw notOurs(source)
        target as? GlesFramebuffer ?: throw notOurs(target)
        Gl.drain("something drawn before a copy")
        Gles.glDisable(Gles.GL_SCISSOR_TEST())
        Gles.glBindFramebuffer(Gles.GL_READ_FRAMEBUFFER(), source.framebuffer)
        Gles.glBindFramebuffer(Gles.GL_DRAW_FRAMEBUFFER(), target.framebuffer)
        val filter = if (from.width == target.width && from.height == target.height) Gles.GL_NEAREST() else Gles.GL_LINEAR()
        Gles.glBlitFramebuffer(from.x, from.y, from.right, from.bottom, 0, 0, target.width, target.height, Gles.GL_COLOR_BUFFER_BIT(), filter)
        Gles.glBindFramebuffer(Gles.GL_FRAMEBUFFER(), 0)
        Gl.check("copying a picture")
        // In the middle of a frame: what is drawn next goes where it went before.
        this.target?.let { bindTarget(it, clip) }
    }

    override fun finish() = Gles.glFinish()

    override fun createBuffer(width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GpuBuffer =
        GlesBuffer.create(egl, images, width, height, format, modifiers, scanout)

    override fun begin(target: GpuTarget, red: Float, green: Float, blue: Float, clip: Rect?, alpha: Float) {
        target as? GlesFramebuffer ?: throw notOurs(target)
        // Errors of the frame before (a shader of yours) are logged now, not blamed on this one.
        Gl.drain("the drawing before this frame")
        this.target = target
        this.clip = clip
        bindTarget(target, clip)
        Gles.glClearColor(red * alpha, green * alpha, blue * alpha, alpha)
        Gles.glClear(Gles.GL_COLOR_BUFFER_BIT())
    }

    override fun setClip(clip: Rect?) {
        this.clip = clip
        // Pixel row y is framebuffer row y (see Shaders.VERTEX), so the clip needs no flipping.
        if (clip != null) {
            Gles.glEnable(Gles.GL_SCISSOR_TEST())
            Gles.glScissor(clip.x, clip.y, clip.width, clip.height)
        } else {
            Gles.glDisable(Gles.GL_SCISSOR_TEST())
        }
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
        alpha: Float, clip: Rect?, cornerRadius: Float, source: FloatArray?, transform: Int,
    ) {
        texture as? GlTexture ?: throw notOurs(texture)
        textured.use()
        setRect(textured, x, y, width, height)
        Gles.glUniform1i(textured.uniform("transform"), transform)
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

    // Users' effects, compiled once each (null: did not compile, see effectProblems).
    private val effects = programCache()
    private val effectProblems = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PROGRAMS
    }

    override fun drawTextureEffect(
        texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, alpha: Float,
        effect: String, from: FloatArray, to: FloatArray, progress: Float,
    ): Boolean {
        texture as? GlTexture ?: throw notOurs(texture)
        val program = effects.compiledOnce(effect) {
            try {
                Program(Shaders.VERTEX, Shaders.effect(effect))
            } catch (e: IllegalStateException) {
                effectProblems[effect] = e.message ?: "$e"
                null
            }
        } ?: return false
        program.use()
        setRect(program, x, y, width, height)
        Gles.glUniform4f(program.uniform("source"), 0f, 0f, 1f, 1f)
        Gles.glActiveTexture(Gles.GL_TEXTURE0())
        Gles.glBindTexture(Gles.GL_TEXTURE_2D(), texture.id)
        Gles.glUniform1i(program.uniform("image"), 0)
        Gles.glUniform1f(program.uniform("alpha"), alpha)
        Gles.glUniform1f(program.uniform("progress"), progress)
        Gles.glUniform4f(program.uniform("from"), from[0], from[1], from[2], from[3])
        Gles.glUniform4f(program.uniform("to"), to[0], to[1], to[2], to[3])
        Gles.glUniform2f(program.uniform("size"), width.toFloat(), height.toFloat())
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
        Gl.drain("an effect shader of yours")
        return true
    }

    override fun effectProblem(effect: String): String? = effectProblems[effect]

    // Whole fragment shaders of users (backgrounds), compiled once each.
    private val shaded = programCache()

    // Shaders compiled so far (for tests: one that fails is not compiled again every frame).
    internal var compilations = 0
        private set

    // The program compiled from [source] — compiled once: a shader that did not compile stays
    // null until its source changes (getOrPut would try again every frame, null being "absent").
    private fun LinkedHashMap<String, Program?>.compiledOnce(source: String, compile: () -> Program?): Program? {
        if (containsKey(source)) return get(source)
        compilations++
        return compile().also { put(source, it) }
    }

    // Compiled shaders, the least used going first past MAX_PROGRAMS (each save of a shader
    // file being edited compiled one more, and the old ones stayed until the end).
    private fun programCache() = object : LinkedHashMap<String, Program?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Program?>?): Boolean {
            if (size <= MAX_PROGRAMS) return false
            eldest?.value?.close()
            return true
        }
    }

    override fun drawShaded(
        x: Int, y: Int, width: Int, height: Int, fragment: String,
        floats: Map<String, FloatArray>, ints: Map<String, Int>, texture: GpuTexture?,
    ): Boolean {
        val program = shaded.compiledOnce(fragment) {
            try {
                Program(Shaders.VERTEX, fragment)
            } catch (e: IllegalStateException) {
                effectProblems[fragment] = e.message ?: "$e"
                null
            }
        } ?: return false
        program.use()
        setRect(program, x, y, width, height)
        Gles.glUniform4f(program.uniform("source"), 0f, 0f, 1f, 1f)
        if (texture != null) {
            texture as? GlTexture ?: throw notOurs(texture)
            Gles.glActiveTexture(Gles.GL_TEXTURE0())
            Gles.glBindTexture(Gles.GL_TEXTURE_2D(), texture.id)
            Gles.glUniform1i(program.uniform("picture"), 0)
        }
        for ((name, value) in floats) {
            val at = program.uniform(name)
            when (value.size) {
                1 -> Gles.glUniform1f(at, value[0])
                2 -> Gles.glUniform2f(at, value[0], value[1])
                4 -> Gles.glUniform4f(at, value[0], value[1], value[2], value[3])
                else -> throw IllegalArgumentException("the shader value $name has ${value.size} numbers: 1, 2 or 4 (a vec3 goes as a vec4)")
            }
        }
        for ((name, value) in ints) Gles.glUniform1i(program.uniform(name), value)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
        Gl.drain("a shader of yours")
        return true
    }

    override fun drawColor(x: Int, y: Int, width: Int, height: Int, red: Float, green: Float, blue: Float, alpha: Float) {
        solid.use()
        setRect(solid, x, y, width, height)
        Gles.glUniform4f(solid.uniform("color"), red * alpha, green * alpha, blue * alpha, alpha)
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
    }

    override fun drawRoundedRect(box: Rect, cornerRadius: Float, color: Color, borderWidth: Float) {
        drawRoundedRect(box, cornerRadius, Paint.Solid(color), borderWidth)
    }

    override fun drawRoundedRect(box: Rect, cornerRadius: Float, paint: Paint, borderWidth: Float, alpha: Float, time: Float): Boolean =
        drawRoundedRect(box, cornerRadius, paint, borderWidth, alpha, time, dev.caulis.render.PaintState.NONE)

    override fun drawRoundedRect(box: Rect, cornerRadius: Float, paint: Paint, borderWidth: Float, alpha: Float, time: Float, state: dev.caulis.render.PaintState): Boolean {
        if (box.isEmpty) return true
        val program = paintedProgram(shadowOf = false, paint) ?: return false
        program.use()
        setRect(program, box.x, box.y, box.width, box.height)
        setPaint(program, paint, box, time, state)
        setBox(program, "outer", box)
        Gles.glUniform1f(program.uniform("radius"), radiusFor(box, cornerRadius))
        Gles.glUniform1f(program.uniform("border"), borderWidth)
        Gles.glUniform1f(program.uniform("alpha"), alpha * paintAlpha(paint))
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
        if (paint is Paint.Shader) Gl.drain("the paint shader ${paint.name}")
        return true
    }

    override fun drawShadow(box: Rect, cornerRadius: Float, softness: Float, color: Color, cutOut: Rect?, cutOutRadius: Float) {
        drawShadow(box, cornerRadius, softness, Paint.Solid(color), cutOut, cutOutRadius)
    }

    override fun drawShadow(box: Rect, cornerRadius: Float, softness: Float, paint: Paint, cutOut: Rect?, cutOutRadius: Float, alpha: Float, time: Float): Boolean {
        if (box.isEmpty) return true
        val program = paintedProgram(shadowOf = true, paint) ?: return false
        val reach = Math.ceil(softness.toDouble()).toInt().coerceAtLeast(1)
        program.use()
        setRect(program, box.x - reach, box.y - reach, box.width + 2 * reach, box.height + 2 * reach)
        setPaint(program, paint, box, time)
        setBox(program, "shape", box)
        Gles.glUniform1f(program.uniform("radius"), radiusFor(box, cornerRadius))
        // The blur fades out at about three sigmas.
        Gles.glUniform1f(program.uniform("sigma"), (softness / 3f).coerceAtLeast(0.5f))
        Gles.glUniform1i(program.uniform("cut"), if (cutOut != null) 1 else 0)
        if (cutOut != null) {
            setBox(program, "cutOut", cutOut)
            Gles.glUniform1f(program.uniform("cutRadius"), radiusFor(cutOut, cutOutRadius))
        }
        Gles.glUniform1f(program.uniform("alpha"), alpha * paintAlpha(paint))
        Gles.glDrawArrays(Gles.GL_TRIANGLE_STRIP(), 0, 4)
        if (paint is Paint.Shader) Gl.drain("the paint shader ${paint.name}")
        return true
    }

    override fun drawBlur(area: Rect, cornerRadius: Float, size: Int, passes: Int, tone: BlurTone) {
        val target = target ?: return
        if (area.isEmpty || size <= 0) return
        blur.blur(target, area, radiusFor(area, cornerRadius), size, passes, tone, clip)
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

    // A shader paint's own strength ([Paint.faded]): colours and gradients carry it in their colours.
    private fun paintAlpha(paint: Paint): Float = (paint as? Paint.Shader)?.alpha ?: 1f

    // A picture or target some other renderer (or your own class) made: this one cannot draw it.
    private fun notOurs(what: Any) =
        IllegalArgumentException("$what was not made by this renderer: GlesRenderer draws only its own textures and targets (from createTexture, createTarget, importDmabuf, ...)")

    // Corners cannot be rounder than half the box.
    private fun radiusFor(box: Rect, radius: Float) = radius.coerceIn(0f, minOf(box.width, box.height) / 2f)

    override fun close() {
        effects.values.forEach { it?.close() }
        painted.values.forEach { it?.close() }
        shaded.values.forEach { it?.close() }
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
        // How many compiled shaders of each kind are kept.
        private const val MAX_PROGRAMS = 64

        /** Opens the GPU (the first render node, or [renderNode]) and prepares drawing. */
        fun create(renderNode: java.nio.file.Path = EglContext.findRenderNode()): GlesRenderer =
            GlesRenderer(EglContext.create(renderNode))

        /**
         * A renderer on an already open GPU device, e.g. the card node the display backend
         * got from libseat (then its buffers can be shown on screen). [device] is not closed.
         */
        fun createOn(device: Fd): GlesRenderer = GlesRenderer(EglContext.createOn(device.value, owned = null))

        /** The same, closing [device] with the renderer when [owned]. */
        fun createOn(device: Fd, owned: Boolean): GlesRenderer = GlesRenderer(EglContext.createOn(device.value, owned = if (owned) device else null))
    }
}

/** A compiled pair of shaders. */
internal class Program(vertexSource: String, fragmentSource: String) : AutoCloseable {

    private val id: Int = Gles.glCreateProgram()
    private val uniforms = HashMap<String, Int>()

    init {
        // A shader of yours that does not compile or link leaves nothing behind on the GPU.
        try {
            val vertex = compile(Gles.GL_VERTEX_SHADER(), vertexSource)
            val fragment = try {
                compile(Gles.GL_FRAGMENT_SHADER(), fragmentSource)
            } catch (e: Exception) {
                Gles.glDeleteShader(vertex)
                throw e
            }
            Gles.glAttachShader(id, vertex)
            Gles.glAttachShader(id, fragment)
            Gles.glLinkProgram(id)
            Gles.glDeleteShader(vertex)
            Gles.glDeleteShader(fragment)
            check(status(Gles::glGetProgramiv, Gles.GL_LINK_STATUS()) == Gles.GL_TRUE()) {
                "Shader program failed to link: ${log(Gles::glGetProgramInfoLog, id)}"
            }
        } catch (e: Exception) {
            Gles.glDeleteProgram(id)
            throw e
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
        if (ok != Gles.GL_TRUE()) {
            val why = log(Gles::glGetShaderInfoLog, shader)
            Gles.glDeleteShader(shader)
            error("Shader failed to compile: $why")
        }
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

private val YUV_FORMATS = setOf(
    "NV12", "NV21", "NV16", "NV61", "NV24", "NV42", "NV15", "NV20", "NV30", "P010", "P012", "P016", "P210", "P030",
    "YUYV", "YVYU", "UYVY", "VYUY", "AYUV", "XYUV", "YU12", "YV12", "YU16", "YV16", "YU24", "YV24",
    "Y210", "Y212", "Y216", "Y410", "Y412", "Y416", "XV30", "XV36", "XV48", "VUY8", "VU24",
)
