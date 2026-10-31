package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.image.BufferedImage
import kotlin.math.ceil

/**
 * The notices on screen: which are shown where, their pictures, timers and the room
 * banners take. Belongs to a [Desktop].
 */
internal class NoticeBoard(
    private val renderer: Renderer,
    private val loop: EventLoop,
    /** Something to draw changed. */
    private val changed: () -> Unit,
    /** The room banners take changed: monitors arrange their panels and windows again. */
    private val roomChanged: () -> Unit,
) {
    private val log = Logger("notices")

    var style = NoticeStyle()
        set(value) {
            val room = field.placement != value.placement
            field = value
            dropTextures()
            if (room) roomChanged()
            changed()
        }

    /** All of them, oldest first (also those still going away). */
    val all = ArrayList<Notice>()

    val listeners = ArrayList<(Notice) -> Unit>()

    private var hovered: Notice? = null

    /** The fade's settings, given its usual time ([NoticeStyle.fadeMillis]); the desktop's "fadeNotices" animation. */
    var fade: (Long) -> ResolvedAnimation = { ResolvedAnimation("fadeNotices", true, it, Curve.SMOOTH, null) }

    private val fadeNanos: Long get() = (fade(style.fadeMillis).effectiveMillis * 1_000_000).coerceAtLeast(1)

    /** True while the pointer is on a notice. */
    val hovering: Boolean get() = hovered != null

    // Each notice's picture per monitor, and what it showed.
    private val textures = HashMap<Pair<Notice, String>, Pair<Any, GpuTexture>>()

    // Where each was drawn last, per monitor, in desktop units (for clicks).
    private val places = HashMap<String, List<Pair<Notice, Rect>>>()
    private val scales = HashMap<String, Double>()

    fun add(level: NoticeLevel, text: String, title: String?, monitorName: String?): Notice {
        // The same message again: counted, its time starts anew.
        all.lastOrNull { it.open && it.level == level && it.text == text && it.title == title }?.let { same ->
            same.count++
            startTimer(same)
            changed()
            return same
        }
        val notice = Notice(level, title, text, if (style.everyMonitor) null else monitorName)
        all += notice
        // Never more than a few dozen waiting: the oldest go.
        while (all.count { it.open } > MAX_KEPT) all.first { it.open }.let { remove(it) }
        log.info { "Shown: $notice" }
        startTimer(notice)
        if (style.placement is NoticePlacement.Banner) roomChanged()
        changed()
        for (listener in listeners.toList()) {
            try {
                listener(notice)
            } catch (e: Exception) {
                log.error("A notice listener failed", e)
            }
        }
        return notice
    }

    fun dismiss(notice: Notice) {
        if (!notice.open) return
        notice.timer?.cancel()
        notice.timer = null
        notice.leavingSince = System.nanoTime()
        log.trace { "Closed: $notice" }
        if (hovered === notice) hovered = null
        if (style.placement is NoticePlacement.Banner) roomChanged()
        changed()
    }

    private fun remove(notice: Notice) {
        notice.timer?.cancel()
        all -= notice
        for (key in textures.keys.filter { it.first === notice }) textures.remove(key)?.second?.close()
        if (notice.open && style.placement is NoticePlacement.Banner) roomChanged()
        notice.leavingSince = notice.leavingSince ?: System.nanoTime()
        changed() // its last faint picture goes too
    }

    private fun startTimer(notice: Notice) {
        notice.timer?.cancel()
        notice.timer = null
        val seconds = style.secondsOf(notice.level)
        if (seconds <= 0) return
        notice.timer = loop.after((seconds * 1000).toLong().coerceAtLeast(1)) {
            notice.timer = null
            // Kept while the pointer is on it (someone is reading it).
            if (hovered === notice) startTimer(notice) else dismiss(notice)
        }
    }

    /** True while one appears or goes away: the picture changes every frame. */
    fun animating(): Boolean {
        val now = System.nanoTime()
        for (notice in all.toList()) {
            val leaving = notice.leavingSince
            if (leaving != null && now - leaving >= fadeNanos) remove(notice)
        }
        val moving = all.any { now - it.shownSince < fadeNanos || it.leavingSince != null }
        if (wasMoving && !moving) changed() // the last frame: fully there (or gone)
        wasMoving = moving
        return moving
    }

    private var wasMoving = false

    // Those shown on [monitor] now, newest first (a notice for a monitor that is gone shows on [fallback]).
    private fun shownOn(monitor: Monitor, monitors: List<Monitor>, fallback: Monitor): List<Notice> {
        if (style.placement is NoticePlacement.Hidden) return emptyList()
        val names = monitors.map { it.name }.toSet()
        fun belongs(notice: Notice) = notice.monitorName == null || notice.monitorName == monitor.name ||
            (notice.monitorName !in names && monitor === fallback)
        return all.asReversed().filter(::belongs).take(style.maxShown)
    }

    /**
     * How many desktop units the banners that make way as [room] says take at the edge of
     * [monitor] (0 for other placements or when none is shown).
     */
    fun bannerRoom(monitor: Monitor, monitors: List<Monitor>, fallback: Monitor, room: NoticePlacement.Banner.Room): Int {
        val banner = style.placement as? NoticePlacement.Banner ?: return 0
        return shownOn(monitor, monitors, fallback).filter { it.open && banner.roomOf(it.level) == room }.sumOf { heightOf(it, monitor.area.width, monitor.scale) }
    }

    // Its height in desktop units when [width] units wide.
    private fun heightOf(notice: Notice, width: Int, scale: Double): Int {
        val look = NoticeLook(Math.round(width * scale).toInt(), 0, scale, notice, hovered = false, style = style)
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = scratch.createGraphics()
        val pixels = try {
            style.painter.height(g, look)
        } catch (e: Exception) {
            log.error("Measuring a notice failed (your NoticePainter?)", e)
            DefaultNoticePainter.height(g, look)
        } finally {
            g.dispose()
        }
        return ceil(pixels.coerceIn(1, 4000) / scale).toInt()
    }

    /** The notices of [monitor] to draw, above everything but the cursor. */
    fun items(monitor: Monitor, monitors: List<Monitor>, fallback: Monitor): List<DrawItem> {
        val shown = shownOn(monitor, monitors, fallback)
        if (shown.isEmpty()) {
            places.remove(monitor.name)
            return emptyList()
        }
        val placed = place(shown, monitor)
        places[monitor.name] = placed
        scales[monitor.name] = monitor.scale
        val now = System.nanoTime()
        val curve = fade(style.fadeMillis).curve
        return placed.map { (notice, rect) ->
            val appearing = ((now - notice.shownSince).toDouble() / fadeNanos).coerceIn(0.0, 1.0)
            val leaving = notice.leavingSince?.let { 1 - ((now - it).toDouble() / fadeNanos).coerceIn(0.0, 1.0) } ?: 1.0
            val alpha = minOf(appearing, leaving).let { t -> if (t >= 1.0) 1.0 else curveAt(curve, t) }.toFloat().coerceIn(0f, 1f)
            DrawItem.Image(NoticeKey(notice, monitor.name), texture(notice, monitor, rect), rect, alpha = alpha)
        }
    }

    // Where each goes on [monitor], in desktop units, newest nearest to the edge.
    private fun place(shown: List<Notice>, monitor: Monitor): List<Pair<Notice, Rect>> {
        val placed = ArrayList<Pair<Notice, Rect>>()
        when (val placement = style.placement) {
            is NoticePlacement.Banner -> {
                val area = monitor.area
                var offset = 0
                // Those that push nearest the edge (in the room made for them); the ones over
                // everything after them.
                val ordered = shown.sortedBy { placement.roomOf(it.level).ordinal }
                for (notice in ordered) {
                    val height = heightOf(notice, area.width, monitor.scale)
                    val y = if (placement.bottom) area.bottom - offset - height else area.y + offset
                    placed += notice to Rect(area.x, y, area.width, height)
                    offset += height
                }
            }
            is NoticePlacement.Toast -> {
                // Clear of panels (their room), but not of banners: there are none then.
                val area = monitor.usableArea
                val margin = placement.margin
                val width = placement.width.coerceAtMost(area.width - 2 * margin).coerceAtLeast(1)
                val corner = placement.corner
                val x = when (corner) {
                    NoticePlacement.Toast.Corner.TOP_LEFT, NoticePlacement.Toast.Corner.BOTTOM_LEFT -> area.x + margin
                    NoticePlacement.Toast.Corner.TOP, NoticePlacement.Toast.Corner.BOTTOM -> area.x + (area.width - width) / 2
                    else -> area.right - margin - width
                }
                val top = corner == NoticePlacement.Toast.Corner.TOP_LEFT || corner == NoticePlacement.Toast.Corner.TOP ||
                    corner == NoticePlacement.Toast.Corner.TOP_RIGHT
                var offset = margin
                for (notice in shown) {
                    val height = heightOf(notice, width, monitor.scale)
                    val y = if (top) area.y + offset else area.bottom - offset - height
                    placed += notice to Rect(x, y, width, height)
                    offset += height + style.gap
                }
            }
            NoticePlacement.Hidden -> {}
        }
        return placed
    }

    private fun texture(notice: Notice, monitor: Monitor, rect: Rect): GpuTexture {
        val scale = monitor.scale
        val width = Math.round(rect.width * scale).toInt().coerceAtLeast(1)
        val height = Math.round(rect.height * scale).toInt().coerceAtLeast(1)
        val shows = listOf(notice.count, notice === hovered, notice.copied, width, height, scale, style)
        val key = notice to monitor.name
        textures[key]?.let { (shown, texture) -> if (shown == shows) return texture else texture.close() }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            style.painter.paint(g, NoticeLook(width, height, scale, notice, notice === hovered, style))
        } catch (e: Exception) {
            log.error("Drawing a notice failed (your NoticePainter?)", e)
        } finally {
            g.dispose()
        }
        val texture = renderer.createTexture(width, height)
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *data), 0, height)
        }
        textures[key] = shows to texture
        return texture
    }

    /** The open notice at the desktop point ([x], [y]), if one is drawn there. */
    fun at(x: Double, y: Double): Notice? = places.values.asSequence().flatten()
        .lastOrNull { (notice, rect) -> notice.open && x >= rect.x && x < rect.right && y >= rect.y && y < rect.bottom }?.first

    /** The button (and its notice) at the desktop point ([x], [y]), if a notice draws one there. */
    fun actionAt(x: Double, y: Double): Pair<Notice, NoticeAction>? {
        for ((monitor, placed) in places) {
            val (notice, rect) = placed.lastOrNull { (n, r) -> n.open && x >= r.x && x < r.right && y >= r.y && y < r.bottom } ?: continue
            val scale = scales[monitor] ?: 1.0
            val look = NoticeLook(Math.round(rect.width * scale).toInt(), Math.round(rect.height * scale).toInt(), scale, notice, notice === hovered, style)
            val action = runCatching { style.painter.actionAt(look, (x - rect.x) * scale, (y - rect.y) * scale) }.getOrNull() ?: return null
            return notice to action
        }
        return null
    }

    /** [notice] was copied: its button shows a check (and it stays a while longer). */
    fun copied(notice: Notice) {
        notice.copied = true
        if (hovered !== notice) startTimer(notice)
        changed()
    }

    /** The pointer moved: the notice under it shows its ×. */
    fun pointerAt(x: Double, y: Double) {
        val now = at(x, y)
        if (now !== hovered) {
            hovered = now
            changed()
        }
    }

    private fun dropTextures() {
        for ((_, cached) in textures) cached.second.close()
        textures.clear()
    }

    fun close() {
        for (notice in all) notice.timer?.cancel()
        dropTextures()
    }

    private data class NoticeKey(val notice: Notice, val monitor: String)

    private companion object {
        const val MAX_KEPT = 50
    }
}
