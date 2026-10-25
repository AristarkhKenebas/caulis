package dev.caulis.screenshot

import dev.caulis.core.ClipboardData
import dev.caulis.core.Desktop
import dev.caulis.core.Monitor
import dev.caulis.ffi.Fd
import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import javax.imageio.ImageIO

/** Pixels read from the GPU: rows of ARGB, top row first. */
internal class Pixels(val width: Int, val height: Int, val argb: IntArray, val transparent: Boolean) {

    /** As a PNG file's bytes. */
    fun png(): ByteArray {
        val image = BufferedImage(width, height, if (transparent) BufferedImage.TYPE_INT_ARGB_PRE else BufferedImage.TYPE_INT_RGB)
        image.raster.setDataElements(0, 0, width, height, if (transparent) argb else IntArray(argb.size) { argb[it] and 0xFFFFFF })
        val bytes = ByteArrayOutputStream(width * height)
        check(ImageIO.write(image, "png", bytes)) { "no PNG writer" }
        return bytes.toByteArray()
    }

    companion object {
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
 * A monitor as it was at one moment (the chooser shows it standing still): drawn without
 * the pointer, and with it.
 */
internal class Frozen private constructor(
    val monitor: Monitor,
    private val renderer: Renderer,
    private val plain: GpuTarget,
    private val withPointer: GpuTarget?,
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
    fun read(region: Rect?, pointer: Boolean = withPointer != null): Pixels {
        val target = if (pointer) withPointer ?: plain else plain
        return Pixels.read(target, region?.let(::toPixels), transparent = false)
    }

    /** [region] in this picture's pixels (at least one, never outside it). */
    fun toPixels(region: Rect): Rect {
        val x0 = Math.round((region.x - area.x) * scale).toInt().coerceIn(0, plain.width - 1)
        val y0 = Math.round((region.y - area.y) * scale).toInt().coerceIn(0, plain.height - 1)
        val x1 = Math.round((region.right - area.x) * scale).toInt().coerceIn(x0 + 1, plain.width)
        val y1 = Math.round((region.bottom - area.y) * scale).toInt().coerceIn(y0 + 1, plain.height)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    override fun close() {
        copy?.close()
        plain.close()
        withPointer?.close()
    }

    companion object {
        /** [monitor] now; with [pointer] null both pictures, else just the one asked for. */
        fun capture(desktop: Desktop, monitor: Monitor, pointer: Boolean? = null): Frozen {
            val width = monitor.pixelWidth
            val height = monitor.pixelHeight
            fun picture(cursor: Boolean) = desktop.renderer.createTarget(width, height).also { desktop.capture(it, cursor, monitor) }
            val plain = picture(cursor = pointer == true)
            val with = if (pointer == null) picture(cursor = true) else null
            return Frozen(monitor, desktop.renderer, plain, with)
        }
    }
}

/** A PNG on the clipboard (written to whoever pastes it on a thread of its own: it may be big). */
internal class PngOnClipboard(private val png: ByteArray) : ClipboardData {
    override val mimeTypes = listOf("image/png")

    override fun send(mimeType: String, fd: Fd) {
        Thread({
            fd.use {
                Arena.ofConfined().use { arena ->
                    val all = arena.allocateFrom(ValueLayout.JAVA_BYTE, *png)
                    var done = 0L
                    while (done < png.size) {
                        val written = it.write(all.asSlice(done))
                        if (written <= 0) break // (the program stopped reading)
                        done += written
                    }
                }
            }
        }, "caulis-screenshot-paste").apply { isDaemon = true }.start()
    }

    override fun toString() = "a screenshot"
}
