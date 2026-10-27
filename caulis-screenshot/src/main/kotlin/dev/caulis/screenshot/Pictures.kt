package dev.caulis.screenshot

import dev.caulis.core.Desktop
import dev.caulis.core.Monitor
import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import javax.imageio.ImageIO

/** Pixels read from the GPU: rows of ARGB (premultiplied), top row first. */
internal class Pixels(val width: Int, val height: Int, val argb: IntArray, val transparent: Boolean) {

    /** As Java's picture, on the same pixels (nothing copied). Without [transparent] the alpha is not looked at. */
    fun image(): BufferedImage {
        val model = if (transparent) ARGB_PRE else RGB
        val raster = java.awt.image.Raster.createPackedRaster(java.awt.image.DataBufferInt(argb, argb.size), width, height, width, model.masks, null)
        return BufferedImage(model, raster, model.isAlphaPremultiplied, null)
    }

    /** As a PNG file's bytes. */
    fun png(): ByteArray {
        val bytes = ByteArrayOutputStream(width * height)
        check(ImageIO.write(image(), "png", bytes)) { "no PNG writer" }
        return bytes.toByteArray()
    }

    /** As a JPEG file's bytes ([quality] 0..1; see-through parts on white). */
    fun jpeg(quality: Float): ByteArray {
        val image = if (!transparent) image() else BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { rgb ->
            rgb.raster.setDataElements(0, 0, width, height, IntArray(argb.size) { i ->
                val p = argb[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0xFF) p and 0xFFFFFF else {
                    // (Premultiplied: over white.)
                    val r = ((p shr 16) and 0xFF) + (255 - a)
                    val g = ((p shr 8) and 0xFF) + (255 - a)
                    val b = (p and 0xFF) + (255 - a)
                    (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
                }
            })
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality.coerceIn(0.1f, 1f)
        }
        val bytes = ByteArrayOutputStream(width * height / 4)
        ImageIO.createImageOutputStream(bytes).use { out ->
            writer.output = out
            writer.write(null, javax.imageio.IIOImage(image, null, null), params)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    /** In [format]'s file bytes. */
    fun encode(format: ShotFormat, jpegQuality: Float): ByteArray = when (format) {
        ShotFormat.PNG -> png()
        ShotFormat.JPEG -> jpeg(jpegQuality)
    }

    companion object {
        private val ARGB_PRE = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE).colorModel as java.awt.image.DirectColorModel
        private val RGB = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).colorModel as java.awt.image.DirectColorModel

        /** [region] of [target] (pixels; null: all of it). */
        fun read(target: GpuTarget, region: Rect?, transparent: Boolean): Pixels {
            val part = region ?: Rect(0, 0, target.width, target.height)
            Arena.ofConfined().use { arena ->
                val memory = arena.allocate(part.width.toLong() * part.height * 4)
                if (region == null) target.readPixels(memory, part.width * 4) else target.readPixels(memory, part.width * 4, part)
                return Pixels(part.width, part.height, memory.toArray(ValueLayout.JAVA_INT), transparent)
            }
        }
    }
}

/**
 * A monitor as it was at one moment (the chooser shows it standing still): drawn without the
 * pointer, and the pointer with what was around it (a small piece, not a second picture of it
 * all) to put in when it is wanted.
 */
internal class Frozen private constructor(
    val monitor: Monitor,
    private val renderer: Renderer,
    private val plain: GpuTarget,
    // The pointer as drawn there (pixels of the monitor), or null: not on this monitor.
    private val pointer: Pair<Rect, Pixels>?,
) : AutoCloseable {

    /** Where the monitor was (desktop units) and its scale then. */
    val area: Rect = monitor.area
    val scale: Double = monitor.scale

    // Some renderers cannot hand out a target's picture: then a copy.
    private var copy: GpuTexture? = null

    /** The picture to show (without the pointer: ours is drawn above it). */
    val texture: GpuTexture
        get() = plain.texture ?: copy ?: run {
            val pixels = Pixels.read(plain, null, transparent = false)
            renderer.createTexture(pixels.width, pixels.height).also { made ->
                Arena.ofConfined().use { arena -> made.upload(arena.allocateFrom(ValueLayout.JAVA_INT, *pixels.argb), 0, pixels.height) }
                copy = made
            }
        }

    /** [region] (desktop units, inside [area]; null: all of it), with the [pointer] or without. */
    fun read(region: Rect?, pointer: Boolean = this.pointer != null): Pixels {
        val part = region?.let(::toPixels) ?: Rect(0, 0, plain.width, plain.height)
        val pixels = Pixels.read(plain, part, transparent = false)
        val (at, cursor) = this.pointer?.takeIf { pointer } ?: return pixels
        // The pointer's piece put in where it overlaps.
        val overlap = at.intersect(part) ?: return pixels
        for (row in overlap.y until overlap.bottom) {
            System.arraycopy(cursor.argb, (row - at.y) * cursor.width + (overlap.x - at.x), pixels.argb, (row - part.y) * pixels.width + (overlap.x - part.x), overlap.width)
        }
        return pixels
    }

    /** [region] in this picture's pixels (at least one, never outside it). */
    fun toPixels(region: Rect): Rect = toPixels(region, area, scale, plain.width, plain.height)

    override fun close() {
        copy?.close()
        plain.close()
    }

    companion object {
        /**
         * [monitor] now; with [pointer] null without the pointer, and the pointer kept aside
         * (the chooser offers both), else just the one asked for.
         */
        fun capture(desktop: Desktop, monitor: Monitor, pointer: Boolean? = null): Frozen {
            val width = monitor.pixelWidth
            val height = monitor.pixelHeight
            fun picture(cursor: Boolean) = desktop.renderer.createTarget(width, height).also { desktop.capture(it, cursor, monitor) }
            val plain = picture(cursor = pointer == true)
            val area = monitor.area
            val x = desktop.pointerX
            val y = desktop.pointerY
            val aside = if (pointer != null || x < area.x || y < area.y || x >= area.right || y >= area.bottom) null else {
                // (Its picture reaches mostly right of and below its tip.)
                val around = toPixels(Rect(x.toInt() - POINTER_BEFORE, y.toInt() - POINTER_BEFORE, POINTER_SIZE, POINTER_SIZE), area, monitor.scale, width, height)
                val with = picture(cursor = true)
                try {
                    around to Pixels.read(with, around, transparent = false)
                } finally {
                    with.close()
                }
            }
            return Frozen(monitor, desktop.renderer, plain, aside)
        }

        // How much around the pointer's tip is kept with it (units).
        private const val POINTER_BEFORE = 64
        private const val POINTER_SIZE = 256

        /** [region] (desktop units) in the pixels of a monitor at [area] and [scale], [width] x [height] pixels (at least one, never outside). */
        internal fun toPixels(region: Rect, area: Rect, scale: Double, width: Int, height: Int): Rect {
            val x0 = Math.round((region.x - area.x) * scale).toInt().coerceIn(0, width - 1)
            val y0 = Math.round((region.y - area.y) * scale).toInt().coerceIn(0, height - 1)
            val x1 = Math.round((region.right - area.x) * scale).toInt().coerceIn(x0 + 1, width)
            val y1 = Math.round((region.bottom - area.y) * scale).toInt().coerceIn(y0 + 1, height)
            return Rect(x0, y0, x1 - x0, y1 - y0)
        }
    }
}

/** Windows' pictures put together into one (on any thread: the GPU's part is done). */
internal class Composite(val width: Int, val height: Int, private val pieces: List<Piece>, private val background: dev.caulis.render.Color?) {

    /**
     * A window's [pixels], drawn at [x], [y], [w] x [h] (pixels of the whole), its corners round
     * by [radius]; null pixels: a black box (a window hidden from screenshots).
     */
    class Piece(val pixels: Pixels?, val x: Double, val y: Double, val w: Double, val h: Double, val radius: Double)

    fun draw(): Pixels {
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = canvas.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        background?.let { c ->
            g.color = c.awt()
            g.fillRect(0, 0, width, height)
        }
        for (piece in pieces) {
            val x = Math.round(piece.x).toInt()
            val y = Math.round(piece.y).toInt()
            val w = Math.round(piece.x + piece.w).toInt() - x
            val h = Math.round(piece.y + piece.h).toInt() - y
            if (piece.pixels == null) {
                g.color = java.awt.Color.BLACK
                g.fill(java.awt.geom.RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), 2 * piece.radius, 2 * piece.radius))
                continue
            }
            val image = piece.pixels.image()
            // At its own size: copied as it is (no blur from scaling by a hair).
            val own = Math.abs(w - image.width) <= 1 && Math.abs(h - image.height) <= 1
            if (piece.radius <= 0.5) {
                if (own) g.drawImage(image, x, y, null) else g.drawImage(image, x, y, w, h, null)
                continue
            }
            // Round corners, smooth: the window on its own, its corners cut away, then put in.
            val cut = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB_PRE)
            val cg = cut.createGraphics()
            cg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            cg.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            if (own) cg.drawImage(image, 0, 0, null) else cg.drawImage(image, 0, 0, w, h, null)
            val corners = java.awt.geom.Area(java.awt.Rectangle(0, 0, w, h))
            corners.subtract(java.awt.geom.Area(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), h.toDouble(), 2 * piece.radius, 2 * piece.radius)))
            cg.composite = java.awt.AlphaComposite.DstOut
            cg.fill(corners)
            cg.dispose()
            g.drawImage(cut, x, y, null)
        }
        g.dispose()
        // (Its own pixels, not copied: the picture is big.)
        val argb = (canvas.raster.dataBuffer as java.awt.image.DataBufferInt).data
        return Pixels(width, height, argb, transparent = background == null || background.alpha < 1f)
    }
}
