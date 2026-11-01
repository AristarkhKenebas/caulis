package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import dev.caulis.render.ShadedPart
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A small thing moving over a [WallpaperShader]'s picture ([MovingParts]). [id] says which one it
 * is from frame to frame; [x], [y] its top left, [width], [height] its size, 0..1 of the picture
 * (it may stick out). Nothing is drawn anew for one that is the same as in the frame before.
 *
 * Drawn each time it changes, blended over the kept picture, by the shader itself with
 * `movingPart` = [kind] (else -1: the kept picture), `movingBox` its place (`at` units), and
 * `movingData`, `movingMore` the numbers of [data] (up to 8). Only its own pixels pay: the shader
 * returns it alone, premultiplied (alpha 0 with a colour: light added).
 *
 * With [kept], the shader draws it only once, into a small picture of its own [keptScale] as sharp,
 * `at` 0..1 over that picture: after that it costs only moving and blending that one, at any place,
 * also between pixels, and [alpha]. Leave a pixel or two empty along its edge. It is drawn again
 * when [data] changes.
 */
class MovingPart @JvmOverloads constructor(
    val id: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val kind: Int = 0,
    val data: FloatArray = NONE,
    val alpha: Float = 1f,
    val kept: Boolean = false,
    val keptScale: Float = 1f,
) {
    init {
        require(width > 0 && height > 0) { "a moving part has a size: $width x $height" }
        require(kind >= 0) { "a moving part's kind is 0 or more, not $kind" }
        require(data.size <= 8) { "a moving part has up to 8 numbers, not ${data.size}" }
        require(keptScale in 0.05f..4f) { "a moving part's keptScale is 0.05 to 4, not $keptScale" }
    }

    override fun equals(other: Any?) = other is MovingPart && id == other.id && x == other.x && y == other.y && width == other.width && height == other.height &&
        kind == other.kind && alpha == other.alpha && kept == other.kept && keptScale == other.keptScale && data.contentEquals(other.data)

    override fun hashCode() = id

    private companion object {
        val NONE = FloatArray(0)
    }
}

/**
 * What moves over a [WallpaperShader]'s picture ([WallpaperShader.movingParts]), which is drawn
 * once and kept: no pixel but a part's is ever worked out again. A hundred small things cost less
 * than a hundredth of the same shader over the whole screen, and a part that did not change in
 * a frame costs nothing: say it the same ([at] can round its time to spare frames). Everything
 * stops while they are all covered or the wallpaper shaders stand still.
 *
 * Implementations must have `equals` (a data class): another one is another picture.
 */
interface MovingParts {
    /** How often [at] is asked: the most any part changes, a second. */
    val framesPerSecond: Int

    /** How long what is kept stays while nothing draws it, in seconds. */
    val keptSeconds: Double get() = 600.0

    /** How long a new size stands still before what is kept is drawn again at it, in seconds. */
    val settleSeconds: Double get() = 0.3

    /** What is kept at its most, times the monitor's pixels each way (4 bytes a pixel). */
    val maxKeptSize: Double get() = 2.0

    /** The parts, bottom to top, at [seconds] of the shaders' clock, on a picture [aspect] times as wide as high. */
    fun at(seconds: Double, aspect: Double): List<MovingPart>
}

/**
 * Parts that stay in their places ([areas]: x, y, width, height, 0..1 of the picture): each is
 * drawn by the shader with `movingPart` its number and `movingData.x` its `time`, rounded so
 * that one part in [every] changes per frame at [framesPerSecond] (so one changes [framesPerSecond]
 * / [every] times a second). For a configuration that names only numbers.
 */
data class FixedMovingParts @JvmOverloads constructor(
    val areas: List<List<Double>>,
    override val framesPerSecond: Int = 10,
    val every: Int = 1,
    override val keptSeconds: Double = 600.0,
    override val settleSeconds: Double = 0.3,
    override val maxKeptSize: Double = 2.0,
) : MovingParts {
    init {
        require(areas.all { it.size == 4 && it[2] > 0 && it[3] > 0 }) { "a moving part is x, y, width, height" }
        require(framesPerSecond in 1..240) { "moving parts' framesPerSecond is 1 to 240, not $framesPerSecond" }
        require(every >= 1) { "moving parts' every is 1 or more, not $every" }
    }

    override fun at(seconds: Double, aspect: Double): List<MovingPart> {
        val frame = floor(seconds.coerceAtLeast(0.0) * framesPerSecond).toLong()
        return areas.mapIndexed { i, a ->
            val own = (i % every).toLong()
            val last = frame - ((frame - own) % every + every) % every
            MovingPart(i, a[0], a[1], a[2], a[3], i, floatArrayOf(last.toFloat() / framesPerSecond))
        }
    }
}

/** The moving parts of the wallpapers shown: what changed from frame to frame, the small kept pictures. By item key; on the loop's thread. */
internal class WallpaperParts {
    private val log = Logger("render")

    /** One wallpaper's parts now, and what changed lately ([changedSince]). */
    class State(val key: Any) {
        var parts: List<MovingPart> = emptyList()
        var rect: Rect? = null
        var version = 0L
        private val changes = ArrayDeque<List<Rect>>()

        fun changed(areas: List<Rect>) {
            version++
            changes.addFirst(areas)
            while (changes.size > HISTORY) changes.removeLast()
        }

        /** What changed since [version] [since] (null: too long ago, or something else). */
        fun changedSince(since: Any?): List<Rect>? {
            val back = (version - (since as? Long ?: return null))
            if (back < 0 || back > changes.size) return null
            val out = ArrayList<Rect>()
            for (i in 0 until back.toInt()) out += changes[i]
            return out
        }

        private companion object {
            const val HISTORY = 16
        }
    }

    private class Kept(val target: GpuTarget, var look: Any?, var used: Long, val keptNanos: Long)

    private val states = HashMap<Any, State>()
    private val kept = HashMap<Pair<Any, Int>, Kept>()
    private val hidden = HashSet<Any>()

    fun isHidden(key: Any) = key in hidden

    /** The parts of [key] are now [parts], on [rect] (desktop units). What that changes is noted for the next frame. */
    fun update(key: Any, parts: List<MovingPart>, rect: Rect): State {
        val state = states.getOrPut(key) { State(key) }
        val same = state.rect == rect
        val before = state.parts
        // (Most frames almost all are the same: the same place in the list.)
        val changed = ArrayList<Rect>()
        if (same) {
            val byId = if (before.size == parts.size) null else before.associateBy { it.id }
            for ((i, now) in parts.withIndex()) {
                val was = if (byId == null) before[i].takeIf { it.id == now.id } ?: before.firstOrNull { it.id == now.id } else byId[now.id]
                if (was == now) continue
                changed += rect.placeOf(now)
                if (was != null) changed += rect.placeOf(was)
            }
            if (before.size != parts.size || byId == null && before.indices.any { before[it].id != parts[it].id }) {
                val ids = parts.mapTo(HashSet()) { it.id }
                for (gone in before) if (gone.id !in ids) changed += rect.placeOf(gone)
            }
        }
        state.parts = parts
        state.rect = rect
        if (changed.isNotEmpty()) {
            state.changed(changed)
            log.trace { "A wallpaper's moving parts: ${changed.size} places drawn anew (${changed.sumOf { it.width.toLong() * it.height }} units of the ${rect.width.toLong() * rect.height})" }
        }
        return state
    }

    private fun Rect.placeOf(part: MovingPart): Rect {
        val left = floor(x + part.x * width).toInt() - 1
        val top = floor(y + part.y * height).toInt() - 1
        return Rect(left, top, ceil(x + (part.x + part.width) * width).toInt() + 1 - left, ceil(y + (part.y + part.height) * height).toInt() + 1 - top)
    }

    /** Remembers whether all parts of [item] are hidden by what is above it in [items]. True if they just came into sight. */
    fun look(item: DrawItem.Custom, items: List<DrawItem>, index: Int): Boolean {
        val state = states[item.key] ?: return false
        val rect = state.rect ?: return false
        val places = state.parts.map { rect.placeOf(it) }
        val covered = places.isNotEmpty() && uncovered(places, items, index, places.size + MAX_PIECES)?.isEmpty() == true
        if (covered) {
            if (hidden.add(item.key)) log.trace { "A wallpaper's moving parts are all covered: no frames for them" }
            return false
        }
        return hidden.remove(item.key).also { if (it) log.trace { "A wallpaper's moving parts show again" } }
    }

    /** Draws what of [state]'s parts is in the clip, over a kept picture at [r] (the frame's pixels). False: the shader does not compile. */
    fun paint(
        key: Any, state: State, r: Rect, look: Any?, settled: Boolean, keptSeconds: Double, source: String,
        floats: Map<String, FloatArray>, ints: Map<String, Int>, picture: GpuTexture?, alpha: Float, renderer: Renderer,
    ): Boolean {
        val clip = renderer.clipNow
        val shaded = ArrayList<ShadedPart>()
        fun flush(): Boolean {
            if (shaded.isEmpty()) return true
            val drawn = renderer.drawShadedParts(source, floats, ints, shaded, picture)
            shaded.clear()
            return drawn
        }
        for (part in state.parts) {
            val px = part.x * r.width + r.x
            val py = part.y * r.height + r.y
            val pw = part.width * r.width
            val ph = part.height * r.height
            val left = floor(px).toInt()
            val top = floor(py).toInt()
            val place = Rect(left, top, ceil(px + pw).toInt() - left, ceil(py + ph).toInt() - top)
            if (clip != null && !clip.overlaps(place)) {
                // (One not in this part of the frame still counts as used.)
                if (part.kept) kept[key to part.id]?.used = System.nanoTime()
                continue
            }
            val part0 = floatArrayOf(((left - r.x).toDouble() / r.width).toFloat(), ((top - r.y).toDouble() / r.height).toFloat(), (place.width.toDouble() / r.width).toFloat(), (place.height.toDouble() / r.height).toFloat())
            if (!part.kept) {
                shaded += ShadedPart(place, part0, partValues(part, part.alpha * alpha, part.x.toFloat(), part.y.toFloat(), part.width.toFloat(), part.height.toFloat()), mapOf("movingPart" to part.kind))
                continue
            }
            if (!flush()) return false
            val texture = keptPicture(key, part, pw, ph, look, settled, keptSeconds, source, floats, ints, picture, renderer) ?: return false
            // Between pixels: the small picture shifted within its place.
            val from = floatArrayOf(((left - px) / pw).toFloat(), ((top - py) / ph).toFloat(), (place.width / pw).toFloat(), (place.height / ph).toFloat())
            renderer.drawTexture(texture, place.x, place.y, place.width, place.height, opaque = false, alpha = part.alpha * alpha, source = from)
        }
        return flush()
    }

    // The values a drawing of [part] reads.
    private fun partValues(part: MovingPart, alpha: Float, x: Float, y: Float, width: Float, height: Float): Map<String, FloatArray> {
        val d = part.data
        fun at(i: Int) = if (i < d.size) d[i] else 0f
        return mapOf(
            "movingBox" to floatArrayOf(x, y, width, height),
            "movingData" to floatArrayOf(at(0), at(1), at(2), at(3)),
            "movingMore" to floatArrayOf(at(4), at(5), at(6), at(7)),
            "alpha" to floatArrayOf(alpha),
        )
    }

    // The small picture of a kept part, drawn if it is new or its look changed.
    private fun keptPicture(
        key: Any, part: MovingPart, pixelsWide: Double, pixelsHigh: Double, look: Any?, settled: Boolean, keptSeconds: Double, source: String,
        floats: Map<String, FloatArray>, ints: Map<String, Int>, picture: GpuTexture?, renderer: Renderer,
    ): GpuTexture? {
        val width = ceil(pixelsWide * part.keptScale).toInt().coerceIn(1, MAX_KEPT_PIXELS)
        val height = ceil(pixelsHigh * part.keptScale).toInt().coerceIn(1, MAX_KEPT_PIXELS)
        val look2 = listOf(look, part.kind, part.data.toList())
        val id = key to part.id
        var entry = kept[id]
        if (entry != null && (entry.look != look2 || settled && (entry.target.width != width || entry.target.height != height))) {
            entry.target.close()
            kept -= id
            entry = null
        }
        if (entry == null) {
            val target = renderer.createTarget(width, height)
            val values = partValues(part, 1f, 0f, 0f, 1f, 1f) + ("size" to floatArrayOf(width.toFloat(), height.toFloat()))
            if (!renderer.drawShadedInto(target, source, floats + values, ints + ("movingPart" to part.kind) + ("movingKept" to 1), picture)) {
                target.close()
                return null
            }
            entry = Kept(target, look2, 0L, (keptSeconds * 1e9).toLong())
            kept[id] = entry
            log.info { "A wallpaper's moving part drawn once and kept: ${width}x$height, ${width * 4L * height / 1024} KB on the GPU" }
        }
        entry.used = System.nanoTime()
        return entry.target.texture
    }

    /** How much GPU memory the kept small pictures take. */
    val bytes: Long get() = kept.values.sumOf { it.target.width * 4L * it.target.height }

    /** Those nothing drew for a while go; so does what is known of a wallpaper not seen. */
    fun letGo(now: Long) {
        kept.entries.removeIf { (_, entry) ->
            (now - entry.used > entry.keptNanos).also { if (it) entry.target.close() }
        }
    }

    fun close() {
        kept.values.forEach { it.target.close() }
        kept.clear()
        states.clear()
        hidden.clear()
    }

    private companion object {
        const val MAX_PIECES = 32

        // The biggest a part's own picture gets, each way.
        const val MAX_KEPT_PIXELS = 2048
    }
}
