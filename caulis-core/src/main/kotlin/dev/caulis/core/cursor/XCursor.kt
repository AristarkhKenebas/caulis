package dev.caulis.core.cursor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One picture of a cursor (one frame of an animated one), [width] x [height] pixels as
 * premultiplied 0xAARRGGBB, row by row. The hotspot is the pixel that points.
 */
class CursorImage(
    val width: Int,
    val height: Int,
    val hotspotX: Int,
    val hotspotY: Int,
    /** How long this frame is shown before the next one, in milliseconds (animated cursors). */
    val delayMillis: Int,
    /** The size the theme made it for (e.g. 24); it may be shown bigger or smaller. */
    val nominalSize: Int,
    val pixels: IntArray,
)

/**
 * Reads cursor files in the Xcursor format, the one all Linux cursor themes use (the files
 * in `/usr/share/icons/<theme>/cursors/`). A file holds the cursor in several sizes, and
 * animated cursors several frames per size.
 */
object XCursor {

    /**
     * The pictures in [file] closest to [size]: one, or several frames of an animation.
     * Null if it is not a valid cursor file — a broken theme must never break the compositor.
     */
    fun read(file: ByteArray, size: Int): List<CursorImage>? = try {
        parse(ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN), size)
    } catch (e: RuntimeException) { // out of bounds, nonsense numbers
        null
    }

    private fun parse(data: ByteBuffer, size: Int): List<CursorImage>? {
        if (data.remaining() < 16 || data.getInt(0) != MAGIC) return null
        val headerSize = data.getInt(4)
        val count = data.getInt(12)
        if (headerSize < 16 || count !in 1..MAX_ENTRIES || headerSize.toLong() + count * 12L > data.limit()) return null
        // The table of contents: (type, nominal size, where).
        val images = (0 until count).map { headerSize + it * 12 }
            .filter { data.getInt(it) == IMAGE_TYPE }
            .map { data.getInt(it + 4) to data.getInt(it + 8) }
        if (images.isEmpty()) return null
        // The size nearest to the one wanted (as libXcursor chooses); all its frames.
        val best = images.map { it.first }.minBy { Math.abs(it - size) }
        val frames = images.filter { it.first == best }.mapNotNull { (_, position) -> image(data, position, best) }
        return frames.ifEmpty { null }
    }

    private fun image(data: ByteBuffer, position: Int, nominal: Int): CursorImage? {
        if (position < 0 || position.toLong() + IMAGE_HEADER > data.limit()) return null
        if (data.getInt(position) != IMAGE_HEADER || data.getInt(position + 4) != IMAGE_TYPE) return null
        val width = data.getInt(position + 16)
        val height = data.getInt(position + 20)
        val hotspotX = data.getInt(position + 24)
        val hotspotY = data.getInt(position + 28)
        val delay = data.getInt(position + 32)
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        if (hotspotX !in 0..width || hotspotY !in 0..height) return null
        val start = position + IMAGE_HEADER
        if (start.toLong() + width.toLong() * height * 4 > data.limit()) return null
        val pixels = IntArray(width * height)
        data.slice(start, width * height * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        return CursorImage(width, height, hotspotX, hotspotY, delay.coerceIn(0, 60_000), nominal, pixels)
    }

    private const val MAGIC = 0x72756358 // "Xcur"
    private const val IMAGE_TYPE = 0xfffd0002.toInt()
    private const val IMAGE_HEADER = 36
    private const val MAX_SIDE = 0x7fff
    private const val MAX_ENTRIES = 0x10000
}
