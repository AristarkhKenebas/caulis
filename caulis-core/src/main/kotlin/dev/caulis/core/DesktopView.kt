package dev.caulis.core

import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.render.GpuTexture
import dev.caulis.render.Rect

/**
 * One screen's view of the [Desktop]. It remembers what was drawn there last time and
 * what changed since, so a frame only redraws the parts that really changed — and no
 * frame is drawn at all while nothing moves.
 *
 * Get one per monitor from [Desktop.createView].
 */
class DesktopView internal constructor() {

    // What the last frame on this screen showed, in drawing order.
    internal var shown: List<DrawItem> = emptyList()

    // Programs waiting to hear when the frame being shown now reaches the screen.
    internal val inFlight = ArrayList<WpPresentationFeedback>()

    // Changed since the last frame (desktop coordinates); null = nothing.
    private var pending: Rect? = null
    private var everything = true

    // Our buffers do not match the screen any more: the next drawn frame must be complete
    // (but this alone is no reason to draw).
    private var fullNextTime = false

    // What each of the last frames redrew, newest first (for buffers drawn longer ago).
    private val history = ArrayDeque<Rect>()

    /** True if something on this screen changed since the last frame. */
    val hasChanges: Boolean get() = everything || pending != null

    internal fun damage(rect: Rect) {
        if (rect.isEmpty) return
        pending = pending?.union(rect) ?: rect
    }

    /**
     * The screen showed something else this frame (a program's own buffer, see direct
     * scanout): the changes so far are dealt with, but our buffers no longer match the
     * screen, so the next frame we draw must be complete.
     */
    internal fun shownWithoutDrawing() {
        pending = null
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
    internal fun takeRepaint(age: Int, bounds: Rect): Rect? {
        val now = if (everything || fullNextTime) bounds else pending
        var repaint = now
        if (age <= 0 || age - 1 > history.size || fullNextTime) {
            repaint = bounds
        } else {
            // The buffer also misses what the frames since it was drawn changed.
            for (i in 0 until age - 1) repaint = repaint?.union(history[i]) ?: history[i]
        }
        history.addFirst(now ?: EMPTY)
        while (history.size > MAX_AGE) history.removeLast()
        pending = null
        everything = false
        fullNextTime = false
        return repaint?.intersect(bounds)
    }

    private companion object {
        const val MAX_AGE = 4
        val EMPTY = Rect(0, 0, 0, 0)
    }
}

/** One thing to draw: a client's picture or a plain coloured rectangle. */
internal sealed class DrawItem {
    abstract val rect: Rect

    /** Identifies the same thing from one frame to the next. */
    abstract val key: Any

    /** Parts that hide whatever is below them (desktop coordinates). */
    abstract val covers: List<Rect>

    class Picture(
        val surface: Surface,
        val texture: GpuTexture,
        override val rect: Rect,
        val opaque: Boolean,
        override val covers: List<Rect>,
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
    ) : DrawItem()

    /** True if [other] (the same thing last frame) looks different apart from its content. */
    fun differsFrom(other: DrawItem): Boolean = when {
        rect != other.rect -> true
        this is Picture && other is Picture -> opaque != other.opaque
        this is Color && other is Color -> red != other.red || green != other.green || blue != other.blue
        else -> true
    }
}

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
        covers += item.covers
    }
    kept.reverse()
    return kept
}
