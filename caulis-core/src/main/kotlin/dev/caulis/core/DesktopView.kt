package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.render.GpuTexture

/**
 * One screen's view of the [Desktop]. It remembers what was drawn there last time and
 * what changed since, so a frame only redraws the parts that really changed — and no
 * frame is drawn at all while nothing moves.
 *
 * Get one per monitor from [Desktop.createView].
 */
class DesktopView internal constructor(monitor: Monitor) {

    /** The monitor this view shows. */
    var monitor: Monitor = monitor
        internal set(value) {
            if (value !== field) everything = true // (another part of the desktop now)
            field = value
        }

    // What the last frame on this screen showed, in drawing order.
    internal var shown: List<DrawItem> = emptyList()

    /** The monitor's cursor plane, if the backend has one for it (the cursor then needs no frames). */
    var hardwareCursor: HardwareCursor? = null
        set(value) {
            if (value === field) return
            // The plane given up shows nothing any more (else its cursor stayed on screen).
            field?.takeIf { planeCursorKey != null }?.hide()
            field = value
            planeCursorKey = null
            planeCursorOffset = null
            cursorOnPlane = false
            everything = true
        }

    // The cursor is on the plane now (so the frames leave it out).
    internal var cursorOnPlane = false
    // What the plane's picture shows (redrawn when this changes).
    internal var planeCursorKey: Any? = null
    // Where the plane's picture is from the pointer (desktop units, from the pointer's whole
    // unit; its size): for moving it at once with the pointer (Desktop.quickCursor). Null: not on the plane.
    internal var planeCursorOffset: Rect? = null

    // True if the last frame drawn here showed the locked session (nothing of the desktop).
    internal var showsLock = false

    // Programs waiting to hear when the frame being shown now reaches the screen.
    internal val inFlight = ArrayList<WpPresentationFeedback>()

    // Changed since the last frame (desktop coordinates), as a few rectangles apart from
    // each other; empty = nothing.
    private val pending = ArrayList<Rect>()
    private var everything = true

    // Our buffers do not match the screen any more: the next drawn frame must be complete
    // (but this alone is no reason to draw).
    private var fullNextTime = false

    // What each of the last frames redrew, newest first (for buffers drawn longer ago).
    private val history = ArrayDeque<List<Rect>>()

    /** True if something on this screen changed since the last frame. */
    val hasChanges: Boolean get() = everything || pending.isNotEmpty()

    // Only what is on this view's monitor counts (a change on another one is no reason to
    // draw a frame here: each monitor has its own picture, blur reads only its own).
    internal fun damage(rect: Rect) {
        if (rect.isEmpty) return
        add(pending, rect.intersect(monitor.area) ?: return)
    }

    /**
     * The screen showed something else this frame (a program's own buffer, see direct
     * scanout): the changes so far are dealt with, but our buffers no longer match the
     * screen, so the next frame we draw must be complete.
     */
    internal fun shownWithoutDrawing() {
        showsLock = false
        pending.clear()
        everything = false
        fullNextTime = true
    }

    /** Everything must be drawn again (e.g. the screen came back from another terminal). */
    fun damageEverything() {
        everything = true
    }

    /**
     * The part to redraw into a buffer whose picture is [age] frames old (1 = it holds the
     * previous frame, 0 = unknown or new: redraw everything). Null if nothing needs drawing.
     */
    internal fun takeRepaint(age: Int, bounds: Rect): Rect? = takeRepaintParts(age, bounds)?.reduce(Rect::union)

    /** The same as [takeRepaint], as a few rectangles apart from each other (null: nothing). */
    internal fun takeRepaintParts(age: Int, bounds: Rect): List<Rect>? {
        val now: List<Rect> = if (everything || fullNextTime) listOf(bounds) else pending.toList()
        val repaint = ArrayList<Rect>()
        if (age <= 0 || age - 1 > history.size || fullNextTime) {
            repaint += bounds
        } else {
            now.forEach { add(repaint, it) }
            // The buffer also misses what the frames since it was drawn changed.
            for (i in 0 until age - 1) history[i].forEach { add(repaint, it) }
        }
        history.addFirst(now)
        while (history.size > MAX_AGE) history.removeLast()
        pending.clear()
        everything = false
        fullNextTime = false
        return repaint.mapNotNull { it.intersect(bounds) }.takeIf { it.isNotEmpty() }
    }

    // Adds [rect] to [parts]: whatever it overlaps is merged with it (so no part is drawn
    // twice), and past [MAX_PARTS] everything becomes one.
    private fun add(parts: MutableList<Rect>, rect: Rect) {
        var merged = rect
        var again = true
        while (again) {
            again = false
            val iterator = parts.iterator()
            while (iterator.hasNext()) {
                val part = iterator.next()
                if (part.intersect(merged) != null) {
                    merged = merged.union(part)
                    iterator.remove()
                    again = true
                }
            }
        }
        parts += merged
        if (parts.size > MAX_PARTS) {
            val all = parts.reduce(Rect::union)
            parts.clear()
            parts += all
        }
    }

    private companion object {
        const val MAX_AGE = 4
        const val MAX_PARTS = 8
        val EMPTY = Rect(0, 0, 0, 0)
    }
}

/** One thing to draw: a client's picture, a plain coloured rectangle, or a window effect. */
internal sealed class DrawItem {
    abstract val rect: Rect

    /** Identifies the same thing from one frame to the next. */
    abstract val key: Any

    /** Parts that hide whatever is below them (desktop coordinates). */
    open val covers: List<Rect> get() = emptyList()

    /**
     * The parts it draws on: all of [rect] for most; only the ring for a border or a shadow
     * (a turning border then redraws a thin frame each frame, not the whole window).
     */
    open val areas: List<Rect> get() = listOf(rect)

    /** Drawn on this monitor only (a workspace sliding there would show on its neighbour too), or null. */
    var only: Monitor? = null

    /** The same, [alpha] as strong (a workspace fading out); its monitor stays. */
    fun fadedBy(alpha: Float): DrawItem = when (this) {
        is Picture -> Picture(surface, texture, rect, opaque, emptyList(), this.alpha * alpha, clip, radius, source)
        is Color -> Color(key, rect, red, green, blue, emptyList(), this.alpha * alpha)
        is Image -> Image(key, texture, rect, clip, radius, this.alpha * alpha)
        is Effect -> Effect(key, texture, rect, this.alpha * alpha, shader, from, to, progress, fallback)
        is Shadow -> copy(alpha = this.alpha * alpha)
        is Border -> copy(alpha = this.alpha * alpha)
        is Tint -> copy(alpha = this.alpha * alpha)
        is Blur, is Custom -> this
    }.also { it.only = only }

    class Picture(
        val surface: Surface,
        val texture: GpuTexture,
        override val rect: Rect,
        val opaque: Boolean,
        override val covers: List<Rect>,
        val alpha: Float = 1f,
        /** Only the part inside is drawn, with [radius] round corners (null = all of it). */
        val clip: Rect? = null,
        val radius: Float = 0f,
        /** The part of the texture shown (wp_viewport), or null for all. */
        val source: FloatArray? = null,
        /** How the program turned its buffer (wl_surface.set_buffer_transform). */
        val transform: Int = 0,
    ) : DrawItem() {
        override val key: Any get() = surface
    }

    class Color(
        override val key: Any,
        override val rect: Rect,
        val red: Float,
        val green: Float,
        val blue: Float,
        override val covers: List<Rect> = emptyList(),
        val alpha: Float = 1f,
    ) : DrawItem()

    /** A picture of our own (a cursor from the theme, a title bar, the frame before a scale change). */
    class Image(
        override val key: Any, val texture: GpuTexture, override val rect: Rect, val clip: Rect? = null, val radius: Float = 0f,
        val alpha: Float = 1f,
    ) : DrawItem() {
        // (New pixels in the same texture: drawn again.)
        val uploads = texture.uploads
    }

    /**
     * A picture drawn through a shader (an animation such as the genie): [from] and [to] are
     * parts of [rect] (0..1); [fallback] is where it goes plainly if the shader does not work.
     */
    class Effect(
        override val key: Any, val texture: GpuTexture, override val rect: Rect, val alpha: Float,
        val shader: WindowShader, val from: FloatArray, val to: FloatArray, val progress: Float, val fallback: Rect,
    ) : DrawItem()

    /**
     * Something that draws itself ([paint] gets its place in the target's pixels): the
     * desktop's background. It looks different when [look] (what it shows) does. A
     * [wallpaper] shader's item asks for [framesPerSecond] frames a second while it is seen
     * (0: none now).
     */
    class Custom(
        override val key: Any,
        override val rect: Rect,
        val look: Any,
        val framesPerSecond: Int = 0,
        val wallpaper: WallpaperShader? = null,
        val paint: (Rect) -> Unit,
    ) : DrawItem()

    /** A window's shadow (cast by [box], blurred over [softness]; [rect] is all it touches). */
    data class Shadow(
        override val key: Any,
        val box: Rect,
        val radius: Float,
        val softness: Float,
        val paint: dev.caulis.render.Paint,
        val cutOut: Rect,
        val cutOutRadius: Float,
        val alpha: Float = 1f,
        /** For a moving paint: seconds (0 otherwise, so the item stays the same). */
        val time: Float = 0f,
    ) : DrawItem() {
        override val rect: Rect = Rect(
            box.x - Math.ceil(softness.toDouble()).toInt(), box.y - Math.ceil(softness.toDouble()).toInt(),
            box.width + 2 * Math.ceil(softness.toDouble()).toInt(), box.height + 2 * Math.ceil(softness.toDouble()).toInt(),
        )

        // Nothing is drawn inside the window casting it (less its round corners).
        override val areas: List<Rect> get() = ring(rect, cutOut, Math.ceil(cutOutRadius.toDouble()).toInt())
    }

    /** A frame [width] wide along the inside of [rect]. */
    data class Border(
        override val key: Any,
        override val rect: Rect,
        val radius: Float,
        val width: Float,
        val paint: dev.caulis.render.Paint,
        val alpha: Float = 1f,
        val time: Float = 0f,
        /** The window's state, for a shader paint. */
        val state: dev.caulis.render.PaintState = dev.caulis.render.PaintState.NONE,
    ) : DrawItem() {
        // A frame along the inside of [rect]: its width plus the round corners.
        override val areas: List<Rect> get() {
            val inset = Math.ceil(width.toDouble()).toInt()
            return ring(rect, Rect(rect.x + inset, rect.y + inset, rect.width - 2 * inset, rect.height - 2 * inset), Math.ceil(radius.toDouble()).toInt())
        }
    }

    /** [rect] darkened (or tinted) by a colour [alpha] strong, its corners rounded by [radius]. */
    data class Tint(
        override val key: Any,
        override val rect: Rect,
        val radius: Float,
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
    ) : DrawItem()

    /** Blurs what is below [rect]; reads pixels up to [reach] around it. */
    data class Blur(
        override val key: Any,
        override val rect: Rect,
        val radius: Float,
        val size: Int,
        val passes: Int,
        val tone: dev.caulis.render.BlurTone = dev.caulis.render.BlurTone.NONE,
    ) : DrawItem() {
        val reach: Int get() = dev.caulis.render.blurReach(size, passes)
    }

    protected fun ring(outer: Rect, hole: Rect, corner: Int): List<Rect> {
        // The hole, less its round corners, is not drawn on; the rest is four strips.
        val inner = Rect(hole.x + corner, hole.y + corner, hole.width - 2 * corner, hole.height - 2 * corner)
        if (inner.width <= 0 || inner.height <= 0 || !outer.contains(inner)) return listOf(outer)
        return listOf(
            Rect(outer.x, outer.y, outer.width, inner.y - outer.y),
            Rect(outer.x, inner.bottom, outer.width, outer.bottom - inner.bottom),
            Rect(outer.x, inner.y, inner.x - outer.x, inner.height),
            Rect(inner.right, inner.y, outer.right - inner.right, inner.height),
        ).filter { it.width > 0 && it.height > 0 }
    }

    /** True if [other] (the same thing last frame) looks different apart from its content. */
    fun differsFrom(other: DrawItem): Boolean = when {
        rect != other.rect -> true
        this is Image && other is Image -> texture !== other.texture || uploads != other.uploads || alpha != other.alpha
        this is Custom && other is Custom -> look != other.look
        this is Effect && other is Effect -> texture !== other.texture || alpha != other.alpha || progress != other.progress || shader != other.shader
        this is Picture && other is Picture -> opaque != other.opaque || alpha != other.alpha || clip != other.clip || radius != other.radius ||
            !java.util.Arrays.equals(source, other.source)
        this is Color && other is Color -> red != other.red || green != other.green || blue != other.blue || alpha != other.alpha
        this is Shadow || this is Border || this is Blur -> this != other
        else -> true
    }
}

/**
 * The parts of [item] (at [index] in [list], bottom to top) a change of it shows on: its
 * [DrawItem.areas], less what opaque things above it cover — for a moving wallpaper shader
 * only (a window over most of it: only the rest is drawn anew). Careful: when cutting would
 * leave more than [MAX_UNCOVERED] pieces, all of its areas.
 */
internal fun uncoveredAreas(item: DrawItem, list: List<DrawItem>, index: Int): List<Rect> {
    if (item !is DrawItem.Custom || item.wallpaper == null) return item.areas
    var parts = item.areas
    for (i in index + 1 until list.size) {
        val above = list[i]
        if (above.only != null) continue // (a sliding workspace hides nothing on the monitor beside)
        for (cover in above.covers) {
            if (parts.none { it.intersect(cover) != null }) continue
            parts = parts.flatMap { it.minus(cover) }
            if (parts.size > MAX_UNCOVERED) return item.areas
            if (parts.isEmpty()) return parts
        }
    }
    return parts
}

private const val MAX_UNCOVERED = 6

/**
 * [parts] (what a frame redraws) with what blurs near them need: a blur reads the pixels
 * within its reach, so the reach of each blur touching a part (and of each blur touching
 * those) comes first, whole — drawn before the blur in the same pass, its pixels are all fresh
 * when the blur reads them — and the parts stay apart otherwise (one box around everything
 * would redraw a whole screen for a strip of wallpaper beside a blurred bar).
 */
internal fun withBlurParts(blurs: List<Rect>, parts: List<Rect>, bounds: Rect): List<Rect> {
    val reaches = blurs.mapNotNull { it.intersect(bounds) }
    val taken = ArrayList<Rect>()
    var grew = true
    while (grew) {
        grew = false
        for (reach in reaches) {
            if (taken.any { it.contains(reach) }) continue
            if (parts.any { it.intersect(reach) != null } || taken.any { it.intersect(reach) != null }) {
                taken += reach
                grew = true
            }
        }
        // Reaches that overlap become one (a blur reads across both).
        var merged = true
        while (merged) {
            merged = false
            loop@ for (a in taken.indices) for (b in a + 1 until taken.size) {
                if (taken[a].intersect(taken[b]) != null) {
                    taken[a] = taken[a].union(taken[b])
                    taken.removeAt(b)
                    merged = true
                    break@loop
                }
            }
        }
    }
    return taken + parts.filter { part -> taken.none { it.contains(part) } }
}

/** Identifies an effect of [owner] (a window or panel) from frame to frame. */
internal data class EffectKey(val kind: String, val owner: Any)

/**
 * Leaves out what nobody can see: items outside [bounds], or completely behind something
 * opaque above them. [items] and the result are bottom to top.
 *
 * Careful rather than exact: an item counts as hidden only if one single opaque rectangle
 * covers all of it, so nothing visible is ever skipped.
 */
internal fun cullHidden(items: List<DrawItem>, bounds: Rect): List<DrawItem> {
    val covers = ArrayList<Rect>()
    val kept = ArrayList<DrawItem>()
    for (item in items.asReversed()) {
        val onScreen = item.rect.intersect(bounds) ?: continue
        if (covers.any { it.contains(onScreen) }) continue
        kept += item
        if (item.only == null) covers += item.covers // (a sliding workspace hides nothing on the monitor beside)
    }
    kept.reverse()
    return kept
}
