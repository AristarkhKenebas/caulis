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
     * A GPU buffer to draw into that others can read: the display (KMS) or another
     * compositor. Allocated with [format] and one of [modifiers].
     */
    fun createBuffer(width: Int, height: Int, format: Int, modifiers: List<Long>, scanout: Boolean): GpuBuffer

    /**
     * Starts drawing a frame into [target]. Only pixels inside [clip] change (null = the
     * whole target); that part is first cleared to the given colour. Drawing only what
     * changed saves most of the work when little moves on screen.
     */
    fun begin(target: GpuTarget, red: Float, green: Float, blue: Float, clip: Rect? = null)

    /** Draws [texture] stretched over the rectangle. [opaque] ignores its alpha (XRGB pictures). */
    fun drawTexture(texture: GpuTexture, x: Int, y: Int, width: Int, height: Int, opaque: Boolean, alpha: Float = 1f)

    /** Fills the rectangle with a colour. */
    fun drawColor(x: Int, y: Int, width: Int, height: Int, red: Float, green: Float, blue: Float, alpha: Float = 1f)

    /**
     * Ends the frame: hands the drawing to the GPU. Whoever reads a [GpuBuffer] afterwards
     * (the display, another compositor) gets the finished picture; the kernel makes them
     * wait for the GPU, so this call itself does not wait.
     */
    fun end()
}

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
