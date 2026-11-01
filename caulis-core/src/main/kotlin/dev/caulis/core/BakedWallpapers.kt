package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Wallpaper shaders with a [MovingArea]: the whole picture drawn once into a picture kept on the
 * GPU, in the shader's own place (`at` 0..1 over all of it: a view moving over it moves that
 * picture, nothing is drawn anew), then only the moving area anew into it, at the shader's pace.
 * One kept picture per thing drawn (by item key): monitors never share one, each has its own
 * size, pointer and place. Everything on the loop's thread, like [ShaderWallpapers].
 */
internal class BakedWallpapers(private val loop: EventLoop, private val changed: () -> Unit) {
    private val log = Logger("render")

    private class Entry(val target: GpuTarget) {
        /** What the kept picture was drawn for (null: it needs drawing). */
        var look: Any? = null

        /** The shader's `size` when it was. */
        var size: FloatArray = floatArrayOf(0f, 0f)

        /** What the moving area was last drawn for, where, and at what `time`. */
        var changing: Any? = NOTHING
        var area: Rect? = null
        var since = 0f
        var used = 0L
    }

    private val entries = HashMap<Any, Entry>()

    // --- how often --------------------------------------------------------------------------------

    /** The frames a second now for [area]: the full [fps] while [busy] or in a burst, else its rest pace. */
    fun pace(area: MovingArea, seconds: Double, busy: Boolean, fps: Int): Int {
        if (area.restFps == 0 || busy) return fps
        if (area.burstEvery > 0 && seconds >= 0 && seconds % area.burstEvery < area.burstFor) return fps
        return minOf(fps, area.restFps)
    }

    // Those last drawn for a burst: all of the area once more, for what the burst left behind.
    private val afterBurst = HashSet<Any>()

    private fun inBurst(area: MovingArea, seconds: Double) = area.burstEvery > 0 && seconds >= 0 && seconds % area.burstEvery < area.burstFor

    /** What of [area] is drawn anew at [seconds]: its [MovingArea.quietArea], but for a burst and the first frame after one. */
    fun partNow(key: Any, area: MovingArea, seconds: Double): MovingArea {
        val quiet = area.quietArea ?: return area
        if (inBurst(area, seconds)) {
            afterBurst += key
            return area
        }
        return if (key in afterBurst) area else quiet
    }

    // --- its size, settling --------------------------------------------------------------------------

    private class Seen(var width: Int, var height: Int, var since: Long)

    private val sizes = HashMap<Any, Seen>()
    private var settle: EventLoop.Timer? = null

    /**
     * True if [rect]'s size has stood still for a moment (or is new): a kept picture of another
     * size is drawn again only then. A view zooming stretches the one kept meanwhile.
     */
    fun settled(key: Any, rect: Rect, now: Long): Boolean {
        val seen = sizes[key]
        if (seen == null) {
            sizes[key] = Seen(rect.width, rect.height, now - SETTLE_NANOS)
            return true
        }
        if (seen.width != rect.width || seen.height != rect.height) {
            seen.width = rect.width
            seen.height = rect.height
            seen.since = now
        }
        if (now - seen.since >= SETTLE_NANOS) return true
        if (settle == null) {
            settle = loop.after(SETTLE_NANOS / 1_000_000 + 1) {
                settle = null
                changed()
            }
        }
        return false
    }

    // --- nothing drawn for what nobody sees -------------------------------------------------------------

    private val hidden = HashSet<Any>()
    private val lastChanging = HashMap<Any, Any?>()

    fun isHidden(key: Any) = key in hidden

    /** What the item says moves: [now], or, while its area is not seen, what it said last (so nothing is drawn anew). */
    fun changing(key: Any, seen: Boolean, now: Any?): Any? {
        if (!seen && key in lastChanging) return lastChanging[key]
        lastChanging[key] = now
        return now
    }

    /** Remembers whether [item]'s moving area is hidden by what is above it in [items]. True if it just came into sight. */
    fun look(item: DrawItem.Custom, items: List<DrawItem>, index: Int): Boolean {
        val area = item.changingArea ?: return false
        val covered = uncovered(listOf(area), items, index, MAX_PIECES)?.isEmpty() == true
        if (covered) {
            if (hidden.add(item.key)) log.trace { "The moving part of a wallpaper shader is covered: no frames for it" }
            return false
        }
        return hidden.remove(item.key).also { if (it) log.trace { "The moving part of a wallpaper shader shows again" } }
    }

    // --- drawing --------------------------------------------------------------------------------------

    /**
     * Shows the kept picture at [r] (the frame's pixels), first drawing what changed: all of it
     * if [look] is not what it was drawn for (or, once [settled], its size is another); the part
     * of [area] (0..1 of the picture) that is in [shown] (the same) if [changing] is not.
     * False: it does not compile.
     */
    fun draw(
        key: Any, r: Rect, area: MovingArea, shown: FloatArray?, resolution: Float, maxWidth: Int, maxHeight: Int, look: Any?, settled: Boolean, changing: Any?, seconds: Float, burstOver: Boolean,
        source: String, moving: Map<String, FloatArray>, still: Map<String, FloatArray>, ints: Map<String, Int>, stillInts: Map<String, Int>,
        picture: GpuTexture?, alpha: Float, renderer: Renderer,
    ): Boolean {
        val width = Math.round(minOf(r.width, maxWidth) * resolution).coerceAtLeast(1)
        val height = Math.round(minOf(r.height, maxHeight) * resolution).coerceAtLeast(1)
        var entry = entries[key]
        // (Another size while it still changes: the one there, stretched; unless what it shows changed too.)
        if (entry != null && (entry.target.width != width || entry.target.height != height) && (settled || entry.look != look)) {
            entry.target.close()
            entries -= key
            entry = null
        }
        if (entry == null) entry = Entry(renderer.createTarget(width, height)).also { entries[key] = it }
        entry.used = System.nanoTime()
        val texture = entry.target.texture ?: return false
        var whole = false
        if (entry.look != look) {
            val size = floatArrayOf(r.width.toFloat(), r.height.toFloat())
            val started = System.nanoTime()
            if (!renderer.drawShadedInto(entry.target, source, still + ("size" to size), stillInts + ("movingWhole" to 1), picture)) {
                entry.look = null
                return false
            }
            entry.look = look
            entry.size = size
            entry.changing = NOTHING
            entry.area = null
            entry.since = seconds
            whole = true
            log.info { "A wallpaper shader drawn whole and kept: ${width}x$height, ${width * 4L * height / 1_048_576} MB on the GPU" }
            log.trace { "(Its commands sent in ${(System.nanoTime() - started) / 1_000_000} ms.)" }
        }
        val part = area.pixels(width, height, shown)
        if (part != null && (entry.changing != changing || entry.area?.contains(part) != true)) {
            if (entry.area?.contains(part) != true) whole = true
            val ints2 = ints + ("movingWhole" to if (whole) 1 else 0)
            val floats = moving + ("size" to entry.size) + ("movingSince" to floatArrayOf(entry.since))
            if (!renderer.redrawShadedInto(entry.target, part, source, floats, ints2, picture)) {
                entry.look = null
                return false
            }
            entry.changing = changing
            entry.area = part
            entry.since = seconds
            if (burstOver) afterBurst -= key
            log.trace { "A wallpaper shader's moving part drawn anew: $part of ${width}x$height${if (whole) ", all of it" else ""}" }
        }
        renderer.drawTexture(texture, r.x, r.y, r.width, r.height, opaque = false, alpha = alpha)
        return true
    }

    // The pixels of a picture of [width] x [height] that are in this area and in [shown] (0..1); null: none.
    private fun MovingArea.pixels(width: Int, height: Int, shown: FloatArray?): Rect? {
        shown ?: return null
        val x0 = maxOf(x, shown[0].toDouble())
        val y0 = maxOf(y, shown[1].toDouble())
        val x1 = minOf(x + this.width, (shown[0] + shown[2]).toDouble())
        val y1 = minOf(y + this.height, (shown[1] + shown[3]).toDouble())
        if (x1 <= x0 || y1 <= y0) return null
        val left = floor(x0 * width).toInt().coerceAtLeast(0)
        val top = floor(y0 * height).toInt().coerceAtLeast(0)
        return Rect(left, top, ceil(x1 * width).toInt().coerceAtMost(width) - left, ceil(y1 * height).toInt().coerceAtMost(height) - top)
    }

    /** True if [area] is not in a burst at [seconds]. */
    fun burstOver(area: MovingArea, seconds: Double) = !inBurst(area, seconds)

    /** How much GPU memory the kept pictures take (for tests and the log). */
    val bytes: Long get() = entries.values.sumOf { it.target.width * 4L * it.target.height }

    /** Kept pictures nothing drew for a while go (a fullscreen window over them, another wallpaper). */
    fun letGo(now: Long) {
        if (entries.isEmpty()) return
        entries.entries.removeIf { (key, entry) ->
            (now - entry.used > KEPT_NANOS).also {
                if (it) {
                    entry.target.close()
                    sizes -= key
                    hidden -= key
                    lastChanging -= key
                    log.info { "A kept wallpaper picture let go (not seen for a while): ${entry.target.width}x${entry.target.height}" }
                }
            }
        }
    }

    fun close() {
        settle?.cancel()
        settle = null
        entries.values.forEach { it.target.close() }
        entries.clear()
    }

    private companion object {
        val NOTHING = Any()

        // How long a size must stand still before the picture is drawn again at it.
        const val SETTLE_NANOS = 300_000_000L

        // How long a kept picture stays while nothing draws it (drawing it again costs one full frame).
        const val KEPT_NANOS = 600_000_000_000L

        const val MAX_PIECES = 32
    }
}
