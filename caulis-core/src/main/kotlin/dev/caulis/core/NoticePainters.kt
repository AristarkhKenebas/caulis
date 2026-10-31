package dev.caulis.core

import dev.caulis.render.Color
import java.awt.BasicStroke
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

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
