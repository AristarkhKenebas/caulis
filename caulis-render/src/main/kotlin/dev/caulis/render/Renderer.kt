package dev.caulis.render

import dev.caulis.geometry.Rect
import java.lang.foreign.MemorySegment

/**
 * Draws Caulis's pictures on the GPU. The rest of Caulis only talks to this interface,
 * so the drawing technology can be swapped: OpenGL ES today ([dev.caulis.render.gles]),
 * Vulkan later.
 *
 * Coordinates are pixels, (0, 0) is the top left corner of the target. Colours are 0..1.
 * A renderer belongs to the thread that created it (the event loop thread).
 */
interface Renderer : AutoCloseable {

    /** Human-readable name of the GPU and driver, for logs. */
    val description: String

    /** A picture of [width]x[height] pixels in BGRA byte order (Wayland's ARGB/XRGB8888). */
    fun createTexture(width: Int, height: Int): GpuTexture

    /** Something to draw into, e.g. one output frame. */
    fun createTarget(width: Int, height: Int): GpuTarget

    /** The GPU's render node (e.g. /dev/dri/renderD128): clients allocate their GPU buffers there. */
    val renderNode: java.nio.file.Path?

    /** GPU buffer formats this renderer can show (for clients' dma-bufs). */
    val dmabufFormats: List<DmabufFormat>

    /**
     * Shows a client's GPU buffer as a texture without copying it. The texture reads the
     * client's memory directly, so the client must not reuse the buffer while it is shown.
     * Returns null if the format is not supported. The [buffer] descriptors stay owned by the caller.
     */
    fun importDmabuf(buffer: Dmabuf): GpuTexture?

    /**
     * A client's GPU buffer to draw into (e.g. a screen recorder's): the picture goes
     * straight into its memory. Null if it cannot be drawn into. Close it when done; the
     * [buffer] descriptors stay owned by the caller.
     */
    fun importDmabufTarget(buffer: Dmabuf): GpuTarget?

    /** Copies the part [from] of [source] into the whole of [target] (on the GPU, stretched if the sizes differ). */
    fun copy(source: GpuTarget, from: Rect, target: GpuTarget)

    /** Waits until the GPU has finished everything asked so far (before others read what was drawn). */
    fun finish()

    /**
     * A GPU buffer to draw into that others can read: the display (KMS) or another
     * compositor. Allocated with [format] and one of [modifiers].
     */
    fun createBuffer(width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GpuBuffer

    /**
     * Starts drawing a frame into [target]. Only pixels inside [clip] change (null = the
     * whole target); that part is first cleared to the given colour ([alpha] 0 = see-through,
     * e.g. for a picture of one window). Drawing only what changed saves most of the work
     * when little moves on screen.
     */
    fun begin(target: GpuTarget, red: Float, green: Float, blue: Float, clip: Rect? = null, alpha: Float = 1f)

    /**
     * Draws [texture] stretched over the rectangle. [opaque] ignores its alpha (XRGB pictures).
     * With a [clip], only the part inside it is drawn, its corners rounded by [cornerRadius]
     * (with soft, anti-aliased edges). With a [source], only that part of the texture is
     * shown (stretched over the rectangle).
     */
    fun drawTexture(
        texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, opaque: Boolean,
        alpha: Float = 1f, clip: Rect? = null, cornerRadius: Float = 0f,
        /** Only this part of the texture (u, v, width, height as fractions), or null for all. */
        source: FloatArray? = null,
    )

    /**
     * Draws [texture] into the rectangle through [effect]: GLSL (OpenGL ES 3) code with a
     * function `vec4 effect(vec2 at)` — [at] is 0..1 in the rectangle, `window(uv)` reads
     * the texture (see-through outside 0..1), `progress`, `from` and `to` (x, y, width,
     * height in the rectangle's 0..1) and `size` (pixels) are there to use. Returns false
     * (drawing nothing) if the code does not compile; [effectProblem] then says why.
     */
    fun drawTextureEffect(
        texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, alpha: Float,
        effect: String, from: FloatArray, to: FloatArray, progress: Float,
    ): Boolean = false

    /** Why [effect] does not compile, or null if it does (or was not tried yet). */
    fun effectProblem(effect: String): String? = "this renderer draws no shaders"

    /**
     * Fills the rectangle with a whole fragment shader of your own ([fragment]: GLSL ES 3
     * with `in vec2 uv`, 0..1 from the top left, and its own `out vec4`). [floats] are set by
     * name (1, 2 or 4 numbers each: float, vec2, vec4), [ints] too; [texture] is `sampler2D picture` if given.
     * Returns false (drawing nothing) if it does not compile; [effectProblem] says why.
     */
    fun drawShaded(
        x: Int, y: Int, width: Int, height: Int, fragment: String,
        floats: Map<String, FloatArray>, ints: Map<String, Int> = emptyMap(), texture: GpuTexture? = null,
    ): Boolean = false

    /** Fills the rectangle with a colour. */
    fun drawColor(x: Int, y: Int, width: Int, height: Int, red: Float, green: Float, blue: Float, alpha: Float = 1f)

    /**
     * Fills [box] with [color], its corners rounded by [cornerRadius]. With a [borderWidth],
     * only a frame that wide along the inside of [box] is drawn (a window border).
     */
    fun drawRoundedRect(box: Rect, cornerRadius: Float, color: Color, borderWidth: Float = 0f)

    /**
     * A soft shadow of the rounded rectangle [box], blurred over [softness] pixels (it reaches
     * that far beyond [box]). Nothing is drawn inside [cutOut] (e.g. the window casting it,
     * so the shadow does not show through a see-through window).
     */
    fun drawShadow(box: Rect, cornerRadius: Float, softness: Float, color: Color, cutOut: Rect? = null, cutOutRadius: Float = cornerRadius)

    /**
     * Blurs what was drawn so far under [area] (frosted glass behind a see-through window),
     * its corners rounded by [cornerRadius]. [size] is how strong the blur is, [passes] how
     * smooth (each pass doubles the reach). It reads pixels up to [blurReach] beyond [area],
     * so those must have been drawn in this frame too.
     */
    fun drawBlur(area: Rect, cornerRadius: Float, size: Int, passes: Int)

    /**
     * Ends the frame: hands the drawing to the GPU. Whoever reads a [GpuBuffer] afterwards
     * (the display, another compositor) gets the finished picture; the kernel makes them
     * wait for the GPU, so this call itself does not wait.
     */
    fun end()
}

/** How far beyond its area [Renderer.drawBlur] with [size] and [passes] reads pixels. */
fun blurReach(size: Int, passes: Int): Int = size * (2 shl passes.coerceIn(1, MAX_BLUR_PASSES))

/** More blur passes than this make no visible difference (and cost a lot). */
const val MAX_BLUR_PASSES = 6

/** A picture on the GPU. */
interface GpuTexture : AutoCloseable {
    val width: Int
    val height: Int

    /**
     * Copies rows [firstRow] until [firstRow] + [rowCount] from [pixels] (tightly packed rows of
     * width * 4 bytes, starting with row [firstRow]). [pixels] must be memory Caulis owns,
     * never a client's shared memory: the driver reads it in C, where a client shrinking the
     * memory would crash the compositor.
     */
    fun upload(pixels: MemorySegment, firstRow: Int, rowCount: Int)
}

/** A picture that can be drawn into. */
interface GpuTarget : AutoCloseable {
    val width: Int
    val height: Int

    /** Copies the finished picture into [destination] as BGRA rows of [stride] bytes, top row first. */
    fun readPixels(destination: MemorySegment, stride: Int)

    /** Copies the part [region] of the picture (rows of [stride] bytes, top row first). */
    fun readPixels(destination: MemorySegment, stride: Int, region: Rect)

    /**
     * The picture drawn into this target, to draw elsewhere (e.g. a window's last look while
     * it goes away), or null if this kind of target cannot be. It belongs to the target.
     */
    val texture: GpuTexture? get() = null
}

/**
 * A [GpuTarget] backed by a shareable GPU buffer. Its memory can be shown by the display
 * (with [handle], a number valid on the renderer's DRM device) or given to others ([export]).
 */
interface GpuBuffer : GpuTarget {
    val format: Int
    val modifier: Long
    val planeCount: Int

    /** GEM handle of a plane on the renderer's DRM device (for drmModeAddFB2). */
    fun handle(plane: Int): Int
    fun stride(plane: Int): Int
    fun offset(plane: Int): Int

    /** New descriptors for this buffer, e.g. to send to another compositor. Caller closes them. */
    fun export(): Dmabuf
}
