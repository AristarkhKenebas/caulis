package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.Color
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.BasicStroke
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** How serious a [Notice] is. */
enum class NoticeLevel { INFO, WARNING, ERROR }

/**
 * A message of the compositor itself on screen: a mistake in the configuration, something
 * that does not work, or whatever your code tells the user with [Desktop.notify]. Clicking
 * it closes it.
 */
class Notice internal constructor(
    val level: NoticeLevel,
    /** A first line in bold, or null. */
    val title: String?,
    val text: String,
    /** The monitor it is shown on (null: every monitor, see [NoticeStyle.everyMonitor]). */
    val monitorName: String?,
) {
    /** How many times it came: the same message again is counted, not shown twice. */
    var count = 1
        internal set

    internal var shownSince = System.nanoTime()
    internal var leavingSince: Long? = null
    internal var timer: EventLoop.Timer? = null

    /** False once it was closed (clicked, timed out, or by [Desktop.dismiss]). */
    val open: Boolean get() = leavingSince == null

    /** Its text was copied (its copy button shows a check). */
    var copied = false
        internal set

    /** What its copy button copies: the title and the text. */
    val fullText: String get() = listOfNotNull(title, text).joinToString("\n")

    override fun toString() = "Notice($level, ${title?.let { "$it: " } ?: ""}$text${if (count > 1) " ×$count" else ""})"
}

/** Where notices are shown (see [NoticeStyle.placement]). */
sealed class NoticePlacement {
    /**
     * A strip across the whole monitor at its top (or [bottom]), as Hyprland shows mistakes
     * in its configuration. [room] says what makes way for it; [errorRoom] the same for
     * errors alone (null: as the others) — e.g. the others over the bar, gone by themselves,
     * and errors pushing everything down until they are closed:
     *
     * ```
     * NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER, errorRoom = Room.PUSH_ALL))
     * ```
     */
    data class Banner @JvmOverloads constructor(
        val bottom: Boolean = false,
        val room: Room = Room.PUSH_ALL,
        val errorRoom: Room? = null,
    ) : NoticePlacement() {
        /** What makes way for a notice of [level]. */
        fun roomOf(level: NoticeLevel): Room = if (level == NoticeLevel.ERROR) errorRoom ?: room else room

        enum class Room {
            /** Windows and panels move aside: nothing is covered. */
            PUSH_ALL,

            /** Over the panels at the edge; windows move aside. */
            PUSH_WINDOWS,

            /** Over everything: nothing moves. */
            COVER,
        }
    }

    /**
     * Cards in a corner (or in the middle of an edge), stacked, as notifications of programs
     * usually are; [width] and [margin] in desktop units. They stay clear of panels.
     */
    data class Toast @JvmOverloads constructor(val corner: Corner = Corner.BOTTOM_RIGHT, val width: Int = 380, val margin: Int = 16) : NoticePlacement() {
        enum class Corner { TOP_LEFT, TOP, TOP_RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

        init {
            require(width in 100..2000) { "a toast is 100 to 2000 units wide" }
            require(margin in 0..500) { "margin must be 0 to 500" }
        }
    }

    /**
     * Not drawn at all: only [Desktop.onNotice] hears them — show them your own way (your
     * panel, a notification daemon, ...).
     */
    data object Hidden : NoticePlacement()
}

/** The colours of one [NoticeLevel]. */
data class NoticeColors(val background: Color, val text: Color)

/**
 * How the compositor's notices look and where they are. Sizes are in desktop units
 * (scaled like everything); for a look of your own, give a [painter].
 *
 * ```
 * NoticeStyle()                                                        // a strip at the top that pushes windows down
 * NoticeStyle(placement = NoticePlacement.Toast())                     // cards at the bottom right
 * NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER))   // a strip over everything
 * ```
 */
data class NoticeStyle @JvmOverloads constructor(
    val placement: NoticePlacement = NoticePlacement.Banner(),
    val font: String = java.awt.Font.SANS_SERIF,
    val fontSize: Int = 13,
    /** Room around the text, between toasts, and their round corners. */
    val padding: Int = 10,
    val gap: Int = 8,
    val radius: Int = 10,
    val info: NoticeColors = NoticeColors(Color.hex("#2b2f38"), Color.hex("#e8eaee")),
    val warning: NoticeColors = NoticeColors(Color.hex("#f5c211"), Color.hex("#1b1b1b")),
    val error: NoticeColors = NoticeColors(Color.hex("#e01b24"), Color.hex("#ffffff")),
    /** How long each level stays, in seconds (0 = until clicked). The pointer on it keeps it. */
    val infoSeconds: Double = 5.0,
    val warningSeconds: Double = 12.0,
    val errorSeconds: Double = 0.0,
    /** At most this many at once (the newest); the rest come when those go. */
    val maxShown: Int = 4,
    /** How long a notice takes to appear and to go (milliseconds). */
    val fadeMillis: Long = 180,
    /** On every monitor, or only on the one that had the focus when it came. */
    val everyMonitor: Boolean = false,
    /** Draws a notice; replace it to draw them entirely your own way. */
    val painter: NoticePainter = DefaultNoticePainter,
) {
    init {
        require(fontSize in 6..72) { "fontSize must be 6 to 72" }
        require(padding in 0..100 && gap in 0..100 && radius in 0..100) { "padding, gap and radius must be 0 to 100" }
        require(infoSeconds >= 0 && warningSeconds >= 0 && errorSeconds >= 0) { "seconds must not be negative" }
        require(maxShown in 1..20) { "maxShown must be 1 to 20" }
        require(fadeMillis in 0..5000) { "fadeMillis must be 0 to 5000" }
    }

    companion object {
        /**
         * Notices as calm cards at the top right of the screen: rounded, [dark] or light, a
         * round mark of the level and the title in bold ([CardNoticePainter]).
         */
        @JvmStatic
        @JvmOverloads
        fun cards(
            width: Int = 400,
            corner: NoticePlacement.Toast.Corner = NoticePlacement.Toast.Corner.TOP_RIGHT,
            dark: Boolean = true,
        ) = NoticeStyle(
            placement = NoticePlacement.Toast(corner, width = width, margin = 14),
            fontSize = 13,
            padding = 12,
            gap = 10,
            radius = 14,
            info = NoticeColors(Color.hex(if (dark) "#88c0d0" else "#5e81ac"), Color.hex("#e5e9f0")),
            warning = NoticeColors(Color.hex(if (dark) "#ebcb8b" else "#c48a2c"), Color.hex("#e5e9f0")),
            error = NoticeColors(Color.hex(if (dark) "#e06c75" else "#bf616a"), Color.hex("#e5e9f0")),
            painter = if (dark) CardNoticePainter() else CardNoticePainter(Color.hex("#f4f6f9f7"), Color.hex("#2e3440")),
        )
    }

    fun colorsOf(level: NoticeLevel): NoticeColors = when (level) {
        NoticeLevel.INFO -> info
        NoticeLevel.WARNING -> warning
        NoticeLevel.ERROR -> error
    }

    fun secondsOf(level: NoticeLevel): Double = when (level) {
        NoticeLevel.INFO -> infoSeconds
        NoticeLevel.WARNING -> warningSeconds
        NoticeLevel.ERROR -> errorSeconds
    }
}

/** What a notice shows right now (for a [NoticePainter]). Sizes in pixels. */
class NoticeLook(
    val width: Int,
    /** 0 while [NoticePainter.height] asks how high it is. */
    val height: Int,
    /** Pixels per desktop unit (to scale fonts and lines). */
    val scale: Double,
    val notice: Notice,
    /** The pointer is on it (a click closes it; its copy button copies it). */
    val hovered: Boolean,
    val style: NoticeStyle,
) {
    /** True for a strip across the monitor, false for a card. */
    val banner: Boolean get() = style.placement is NoticePlacement.Banner
}

/** Draws a notice into a [NoticeLook.width] x [NoticeLook.height] picture (see-through where nothing is drawn). */
interface NoticePainter {
    /** How high (in pixels) [look] is at its width; [g] is there to measure text. */
    fun height(g: Graphics2D, look: NoticeLook): Int = DefaultNoticePainter.height(g, look)

    fun paint(g: Graphics2D, look: NoticeLook)

    /** The button of [look] at ([x], [y]) (pixels in it), if it draws one there; null: a click closes the notice. */
    fun actionAt(look: NoticeLook, x: Double, y: Double): NoticeAction? = null
}

/** What a notice's button does. */
enum class NoticeAction {
    /** Its text goes to the clipboard (to send it to someone). */
    COPY,

    /** It closes. */
    CLOSE,
}

/** The usual notice: its colour, the title in bold, the text wrapped to fit, × on hover. */
object DefaultNoticePainter : NoticePainter {

    // Its copy button: left of the ×, in the top row (see copyButton).
    override fun actionAt(look: NoticeLook, x: Double, y: Double): NoticeAction? {
        val unit = look.scale
        val cx = look.width - look.style.padding * unit - COPY_FROM_RIGHT * unit
        return if (Math.abs(x - cx) <= 10 * unit && y <= (look.style.padding + 18) * unit) NoticeAction.COPY else null
    }

    override fun height(g: Graphics2D, look: NoticeLook): Int {
        val (lines, metrics) = lines(g, look)
        val padding = (look.style.padding * look.scale).toInt()
        return lines.size * metrics.height + 2 * padding
    }

    override fun paint(g: Graphics2D, look: NoticeLook) {
        val style = look.style
        fun awt(c: Color) = java.awt.Color(c.red, c.green, c.blue, c.alpha)
        val colors = style.colorsOf(look.notice.level)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = awt(colors.background)
        if (look.banner) {
            g.fillRect(0, 0, look.width, look.height)
        } else {
            val r = style.radius * look.scale * 2
            g.fill(RoundRectangle2D.Double(0.0, 0.0, look.width.toDouble(), look.height.toDouble(), r, r))
        }
        val (lines, metrics) = lines(g, look)
        val padding = (style.padding * look.scale).toInt()
        var y = padding + metrics.ascent
        for ((index, line) in lines.withIndex()) {
            g.font = if (index == 0 && look.notice.title != null) bold(look) else plain(look)
            g.color = awt(colors.text)
            g.drawString(line, padding, y)
            y += metrics.height
        }
        if (look.hovered) {
            // A × at the right: a click closes it.
            val unit = look.scale
            val cx = look.width - padding - 6 * unit
            val cy = padding + metrics.height / 2.0
            val r = 4.5 * unit
            g.stroke = BasicStroke((1.4 * unit).toFloat())
            g.draw(Line2D.Double(cx - r, cy - r, cx + r, cy + r))
            g.draw(Line2D.Double(cx - r, cy + r, cx + r, cy - r))
            copyButton(g, look, cy)
        }
    }

    /**
     * The copy button left of the ×, at height [cy] (pixels): two sheets, or a check once
     * copied. A click there copies the notice instead of closing it (see [NoticeBoard.copyAt]).
     */
    internal fun copyButton(g: Graphics2D, look: NoticeLook, cy: Double) {
        val unit = look.scale
        val cx = look.width - look.style.padding * unit - COPY_FROM_RIGHT * unit
        g.stroke = BasicStroke((1.3 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (look.notice.copied) {
            g.draw(Line2D.Double(cx - 4.5 * unit, cy, cx - 1.2 * unit, cy + 3.5 * unit))
            g.draw(Line2D.Double(cx - 1.2 * unit, cy + 3.5 * unit, cx + 5 * unit, cy - 4 * unit))
        } else {
            val s = 7.0 * unit
            g.draw(RoundRectangle2D.Double(cx - s / 2 - 1.5 * unit, cy - s / 2 - 1.5 * unit, s, s, 2.5 * unit, 2.5 * unit))
            g.draw(RoundRectangle2D.Double(cx - s / 2 + 1.5 * unit, cy - s / 2 + 1.5 * unit, s, s, 2.5 * unit, 2.5 * unit))
        }
    }

    /** Where the copy button's middle is: this far left of the right edge's padding (desktop units). */
    internal const val COPY_FROM_RIGHT = 24.0

    internal fun plain(look: NoticeLook) = Font(look.style.font, Font.PLAIN, 1).deriveFont((look.style.fontSize * look.scale).toFloat())
    internal fun bold(look: NoticeLook) = Font(look.style.font, Font.BOLD, 1).deriveFont((look.style.fontSize * look.scale).toFloat())

    // The lines it shows: the title, then the text wrapped at word ends (and its own line ends),
    // in [inset] pixels less than the picture's width beyond the padding.
    internal fun lines(g: Graphics2D, look: NoticeLook, inset: Int = 0): Pair<List<String>, FontMetrics> {
        val metrics = g.getFontMetrics(plain(look))
        val padding = (look.style.padding * look.scale).toInt()
        val room = (look.width - 2 * padding - inset - (16 * look.scale).toInt()).coerceAtLeast(1) // (room for the ×)
        val count = if (look.notice.count > 1) " (×${look.notice.count})" else ""
        val lines = ArrayList<String>()
        look.notice.title?.let { lines += it + count }
        for (paragraph in (look.notice.text + if (look.notice.title == null) count else "").split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val tried = if (line.isEmpty()) word else "$line $word"
                if (metrics.stringWidth(tried) <= room || line.isEmpty()) {
                    line = tried
                } else {
                    lines += line
                    line = word
                }
                // A word too long for a line on its own is cut.
                while (metrics.stringWidth(line) > room && line.length > 1) {
                    var cut = line.length - 1
                    while (cut > 1 && metrics.stringWidth(line.substring(0, cut)) > room) cut--
                    lines += line.substring(0, cut)
                    line = line.substring(cut)
                }
            }
            lines += line
        }
        return lines to metrics
    }
}

/**
 * Notices as cards: calm, rounded, light or dark ([background], [text]), a round mark of the
 * level on the left (i, !, ×) in the level's colour, the title in bold and the rest a little
 * fainter ([NoticeStyle.cards] sets it up). The level's colours of the style
 * ([NoticeStyle.info], ...) are the marks' here.
 */
class CardNoticePainter @JvmOverloads constructor(
    private val background: Color = Color.hex("#2a2e37f5"),
    private val text: Color = Color.hex("#eceff4"),
) : NoticePainter {

    // The mark's room on the left, the most lines shown, the buttons' row (desktop units).
    private val markRoom = 34.0
    private val maxLines = 8
    private val buttonRow = 30.0

    // Warnings and errors have buttons (Copy, Close) in a row of their own below the text.
    private fun hasButtons(look: NoticeLook) = look.notice.level != NoticeLevel.INFO && !look.banner

    // The text's lines: at most [maxLines], the last cut with "…" when there are more.
    private fun shownLines(g: Graphics2D, look: NoticeLook): Pair<List<String>, java.awt.FontMetrics> {
        val (lines, metrics) = DefaultNoticePainter.lines(g, look, (markRoom * look.scale).toInt())
        if (lines.size <= maxLines) return lines to metrics
        return (lines.take(maxLines - 1) + (lines[maxLines - 1].trimEnd() + " …")) to metrics
    }

    override fun height(g: Graphics2D, look: NoticeLook): Int {
        val (lines, metrics) = shownLines(g, look)
        val padding = (look.style.padding * look.scale).toInt()
        val textHeight = lines.size * metrics.height + (if (look.notice.title != null && lines.size > 1) (3 * look.scale).toInt() else 0)
        return maxOf(textHeight, (24 * look.scale).toInt()) + 2 * padding + if (hasButtons(look)) (buttonRow * look.scale).toInt() else 0
    }

    // The buttons (pixels): Close at the right, Copy left of it, in the bottom row.
    private fun buttons(look: NoticeLook): List<Pair<NoticeAction, java.awt.geom.Rectangle2D.Double>> {
        if (!hasButtons(look)) return emptyList()
        val u = look.scale
        val padding = look.style.padding * u
        val h = 22 * u
        val y = look.height - padding - h
        val closeW = 58 * u
        val copyW = (if (look.notice.copied) 74 else 58) * u
        val close = java.awt.geom.Rectangle2D.Double(look.width - padding - closeW, y, closeW, h)
        val copy = java.awt.geom.Rectangle2D.Double(close.x - 6 * u - copyW, y, copyW, h)
        return listOf(NoticeAction.COPY to copy, NoticeAction.CLOSE to close)
    }

    override fun actionAt(look: NoticeLook, x: Double, y: Double): NoticeAction? {
        buttons(look).firstOrNull { (_, r) -> r.contains(x, y) }?.let { return it.first }
        // The × at the top right (on hover) closes; a click anywhere else on a warning keeps it.
        val u = look.scale
        val padding = look.style.padding * u
        if (x >= look.width - padding - 16 * u && y <= padding + 16 * u) return NoticeAction.CLOSE
        return null
    }

    override fun paint(g: Graphics2D, look: NoticeLook) {
        fun awt(c: Color) = java.awt.Color(c.red, c.green, c.blue, c.alpha)
        val style = look.style
        val accent = style.colorsOf(look.notice.level).background
        val unit = look.scale
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val w = look.width.toDouble()
        val h = look.height.toDouble()
        val r = if (look.banner) 0.0 else style.radius * unit * 2
        val card = RoundRectangle2D.Double(0.5 * unit, 0.5 * unit, w - unit, h - unit, r, r)
        g.color = awt(background)
        g.fill(card)
        g.color = awt(text.withAlpha(0.09f))
        g.stroke = BasicStroke(unit.toFloat())
        g.draw(card)

        val padding = (style.padding * unit).toInt()
        // The mark: a soft circle of the level's colour with its sign.
        val mr = 11.0 * unit
        val mx = padding + mr
        val my = padding + 12.0 * unit
        g.color = awt(accent.withAlpha(0.22f))
        g.fill(java.awt.geom.Ellipse2D.Double(mx - mr, my - mr, 2 * mr, 2 * mr))
        g.color = awt(accent)
        g.stroke = BasicStroke((1.8 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (look.notice.level) {
            NoticeLevel.INFO -> {
                g.draw(Line2D.Double(mx, my - 1 * unit, mx, my + 5 * unit))
                g.fill(java.awt.geom.Ellipse2D.Double(mx - 1.3 * unit, my - 5.6 * unit, 2.6 * unit, 2.6 * unit))
            }
            NoticeLevel.WARNING -> {
                g.draw(Line2D.Double(mx, my - 5 * unit, mx, my + 1 * unit))
                g.fill(java.awt.geom.Ellipse2D.Double(mx - 1.3 * unit, my + 3.2 * unit, 2.6 * unit, 2.6 * unit))
            }
            NoticeLevel.ERROR -> {
                val c = 4.2 * unit
                g.draw(Line2D.Double(mx - c, my - c, mx + c, my + c))
                g.draw(Line2D.Double(mx - c, my + c, mx + c, my - c))
            }
        }

        val (lines, metrics) = shownLines(g, look)
        val left = padding + (markRoom * unit).toInt()
        var y = if (lines.size == 1) (my + metrics.ascent / 2.0 - metrics.descent / 2.0).toInt() else padding + metrics.ascent
        for ((index, line) in lines.withIndex()) {
            val title = index == 0 && look.notice.title != null
            g.font = if (title) DefaultNoticePainter.bold(look) else DefaultNoticePainter.plain(look)
            g.color = awt(if (title) text else text.withAlpha(text.alpha * 0.78f))
            g.drawString(line, left, y)
            y += metrics.height + if (title) (3 * unit).toInt() else 0
        }

        // The buttons, below the text.
        g.font = DefaultNoticePainter.plain(look).deriveFont((DefaultNoticePainter.plain(look).size2D * 0.92f))
        val fm = g.fontMetrics
        for ((action, b) in buttons(look)) {
            val label = when (action) {
                NoticeAction.COPY -> if (look.notice.copied) "Copied ✓" else "Copy"
                NoticeAction.CLOSE -> "Close"
            }
            g.color = awt(if (action == NoticeAction.COPY && look.notice.copied) accent.withAlpha(0.22f) else text.withAlpha(0.09f))
            g.fill(RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 12 * unit, 12 * unit))
            g.color = awt(if (action == NoticeAction.COPY && look.notice.copied) accent else text.withAlpha(0.85f))
            g.drawString(label, (b.x + (b.width - fm.stringWidth(label)) / 2).toFloat(), (b.y + (b.height + fm.ascent - fm.descent) / 2).toFloat())
        }

        if (look.hovered) {
            val cx = w - padding - 6 * unit
            val cy = padding + 7.0 * unit
            val cr = 4.0 * unit
            g.color = awt(text.withAlpha(0.6f))
            g.stroke = BasicStroke((1.4 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Double(cx - cr, cy - cr, cx + cr, cy + cr))
            g.draw(Line2D.Double(cx - cr, cy + cr, cx + cr, cy - cr))
        }
    }
}

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

    private val fadeNanos: Long get() = (style.fadeMillis * 1_000_000).coerceAtLeast(1)

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
        return placed.map { (notice, rect) ->
            val appearing = ((now - notice.shownSince).toDouble() / fadeNanos).coerceIn(0.0, 1.0)
            val leaving = notice.leavingSince?.let { 1 - ((now - it).toDouble() / fadeNanos).coerceIn(0.0, 1.0) } ?: 1.0
            val alpha = minOf(appearing, leaving).let { t -> t * t * (3 - 2 * t) }.toFloat()
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
