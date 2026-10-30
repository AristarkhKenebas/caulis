package dev.caulis.screenshot

import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/**
 * The chooser's toolbar: the kinds (in a group of their own), how a part is chosen, the
 * pointer, the timer, Capture and close — pictures only, their names in a tip above the one
 * under the pointer. Drawn with Java's 2D graphics; each look is kept, so going over the
 * buttons only picks a picture made before.
 */
internal class Toolbar(private val renderer: Renderer, val buttons: List<ChooserButton>, private val style: ScreenshotStyle) : AutoCloseable {

    /** What it shows. */
    data class Look(
        val kind: ShotKind,
        val selection: Screenshots.Selection,
        val pointer: Boolean,
        val delay: Int,
        val hovered: ChooserButton?,
        val scale: Double,
        val style: ScreenshotStyle,
        /** The pointer means nothing for what would be taken now (greyed out). */
        val pointerIdle: Boolean = false,
    )

    // The looks made (a few: the buttons gone over), the oldest let go.
    private val made = object : LinkedHashMap<Look, GpuTexture>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Look, GpuTexture>): Boolean {
            if (size <= 16) return false
            eldest.value.close()
            return true
        }
    }

    // A button's width: Capture as wide as its word (in its language) needs.
    private fun widthOf(button: ChooserButton): Int =
        if (button != ChooserButton.TAKE) style.buttonWidth
        else maxOf(style.buttonWidth, textWidth(style.words.capture, captureFont(style)) + 2 * style.capturePadding)

    /** Where each button is, from the toolbar's left edge (units). */
    val places: Map<ChooserButton, IntRange> = buildMap {
        var x = style.toolbarPadding
        var last: ChooserButton? = null
        for (button in buttons) {
            // A gap between the groups: the kinds, the options, Capture.
            if (last != null && (last.isKind != button.isKind || button == ChooserButton.TAKE)) x += style.groupGap
            val w = widthOf(button)
            put(button, x until x + w)
            x += w
            last = button
        }
    }

    /** Its size in desktop units. */
    val width = (places.values.maxOfOrNull { it.last + 1 } ?: 0) + style.toolbarPadding
    val height = style.toolbarHeight

    /** The button at [x], [y] of a toolbar at [at] (desktop units), or null. */
    fun buttonAt(at: dev.caulis.geometry.Rect, x: Double, y: Double): ChooserButton? {
        if (y < at.y + 4 || y >= at.bottom - 4) return null
        val local = (x - at.x).toInt()
        return places.entries.firstOrNull { local in it.value }?.key
    }

    /** Its picture for [look]. */
    fun texture(look: Look): GpuTexture = made.getOrPut(look) {
        val s = look.scale
        picture(Math.round(width * s).toInt(), Math.round(height * s).toInt(), renderer) { g ->
            g.scale(s, s)
            paint(g, look)
        }
    }

    private fun paint(g: Graphics2D, look: Look) {
        val st = look.style
        // A panel with soft corners and square-ish tiles on it (as a phone's quick settings).
        val r = (2.0 * st.cornerRadius).coerceAtMost(height.toDouble())
        g.color = st.background.awt()
        g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), r, r))
        g.color = st.border.awt()
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, r, r))
        val tile = 2.0 * st.buttonRadius
        val inset = st.buttonInset.toDouble()
        for ((button, range) in places) {
            val x = range.first.toDouble()
            val w = (range.last + 1 - range.first).toDouble()
            val cx = x + w / 2
            val cy = height / 2.0
            val box = RoundRectangle2D.Double(x + 3, inset, w - 6, height - 2 * inset, tile, tile)
            val hovered = look.hovered == button
            if (button == ChooserButton.TAKE) {
                g.color = st.accent.awt()
                g.fill(box)
                if (hovered) {
                    g.color = java.awt.Color(1f, 1f, 1f, 0.14f)
                    g.fill(box)
                }
                g.color = st.onAccent.awt()
                g.font = captureFont(st)
                centred(g, st.words.capture, cx, cy + (g.fontMetrics.ascent - g.fontMetrics.descent) / 2.0)
                continue
            }
            // (The pointer where it is not in the picture: shown as off.)
            val on = when (button) {
                ChooserButton.REGION -> look.kind == ShotKind.REGION
                ChooserButton.WINDOW -> look.kind == ShotKind.WINDOW
                ChooserButton.SCREEN -> look.kind == ShotKind.SCREEN
                ChooserButton.WINDOWS -> look.kind == ShotKind.WINDOWS
                ChooserButton.POINTER -> look.pointer && !look.pointerIdle
                ChooserButton.TIMER -> look.delay > 0
                else -> false
            }
            // How a part is chosen means something for a part only; the pointer only where it can be in the picture.
            val idle = (button == ChooserButton.SELECTION && look.kind != ShotKind.REGION) || (button == ChooserButton.POINTER && look.pointerIdle)
            g.color = when {
                on -> st.accent.awt()
                idle -> st.text.awt(0.04f)
                hovered -> st.text.awt(0.15f)
                else -> st.text.awt(0.08f)
            }
            g.fill(box)
            if (on && hovered) {
                g.color = java.awt.Color(1f, 1f, 1f, 0.12f)
                g.fill(box)
            }
            g.color = when {
                on -> st.onAccent.awt()
                idle -> st.text.awt(0.3f)
                else -> st.text.awt()
            }
            g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            icon(g, button, cx, cy, look)
            // The timer's seconds, small, at its corner.
            if (button == ChooserButton.TIMER && look.delay > 0) {
                g.font = Font(st.font, Font.BOLD, 1).deriveFont(st.fontSize - 2f)
                val text = look.delay.toString()
                val tw = g.fontMetrics.stringWidth(text).toDouble()
                val bw = maxOf(14.0, tw + 6)
                g.color = st.onAccent.awt()
                g.fill(RoundRectangle2D.Double(x + w - 3 - bw - 2, inset + 2, bw, 13.0, 8.0, 8.0))
                g.color = st.accent.awt()
                g.drawString(text, (x + w - 5 - bw + (bw - tw) / 2).toFloat(), (inset + 12.5).toFloat())
            }
        }
    }

    // Line pictures about 22 units across, centred on [cx], [cy].
    private fun icon(g: Graphics2D, button: ChooserButton, cx: Double, cy: Double, look: Look) {
        val dashed = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3.2f), 0f)
        val plain = g.stroke
        when (button) {
            ChooserButton.REGION -> {
                g.stroke = dashed
                g.draw(RoundRectangle2D.Double(cx - 10, cy - 7.5, 20.0, 15.0, 4.0, 4.0))
                g.stroke = plain
                g.draw(Line2D.Double(cx - 11.5, cy - 9.5, cx - 11.5, cy - 4.5)); g.draw(Line2D.Double(cx - 11.5, cy - 9.5, cx - 6.5, cy - 9.5))
                g.draw(Line2D.Double(cx + 11.5, cy + 9.5, cx + 11.5, cy + 4.5)); g.draw(Line2D.Double(cx + 11.5, cy + 9.5, cx + 6.5, cy + 9.5))
            }
            ChooserButton.WINDOW -> {
                g.draw(RoundRectangle2D.Double(cx - 10, cy - 8, 20.0, 16.0, 5.0, 5.0))
                g.fill(Ellipse2D.Double(cx - 7, cy - 5.2, 2.4, 2.4))
                g.fill(Ellipse2D.Double(cx - 3.4, cy - 5.2, 2.4, 2.4))
                g.draw(Line2D.Double(cx - 10, cy - 1.5, cx + 10, cy - 1.5))
            }
            ChooserButton.SCREEN -> {
                g.draw(RoundRectangle2D.Double(cx - 11, cy - 9, 22.0, 14.5, 4.0, 4.0))
                g.draw(Line2D.Double(cx - 4.5, cy + 9, cx + 4.5, cy + 9))
                g.draw(Line2D.Double(cx, cy + 5.5, cx, cy + 9))
            }
            ChooserButton.WINDOWS -> {
                // Three windows, whole, inside a frame chosen around them.
                g.stroke = dashed
                g.draw(RoundRectangle2D.Double(cx - 12, cy - 10, 24.0, 20.0, 5.0, 5.0))
                g.stroke = plain
                g.fill(RoundRectangle2D.Double(cx - 8, cy - 6, 8.5, 12.0, 3.0, 3.0))
                g.fill(RoundRectangle2D.Double(cx + 2, cy - 6, 6.0, 5.2, 2.5, 2.5))
                g.fill(RoundRectangle2D.Double(cx + 2, cy + 0.8, 6.0, 5.2, 2.5, 2.5))
            }
            ChooserButton.SELECTION -> if (look.selection == Screenshots.Selection.ADJUST) {
                // A part with its handles: drag it and its edges.
                g.stroke = dashed
                g.draw(Rectangle2D.Double(cx - 9, cy - 7, 18.0, 14.0))
                g.stroke = plain
                for ((hx, hy) in listOf(cx - 9 to cy - 7, cx + 9 to cy - 7, cx - 9 to cy + 7, cx + 9 to cy + 7)) {
                    g.fill(Rectangle2D.Double(hx - 2.3, hy - 2.3, 4.6, 4.6))
                }
            } else {
                // A part drawn anew: a frame and a plus.
                g.stroke = dashed
                g.draw(Rectangle2D.Double(cx - 10, cy - 8, 14.0, 11.0))
                g.stroke = plain
                g.draw(Line2D.Double(cx + 6.5, cy + 1.5, cx + 6.5, cy + 10.5))
                g.draw(Line2D.Double(cx + 2, cy + 6, cx + 11, cy + 6))
            }
            ChooserButton.POINTER -> {
                val arrow = Path2D.Double().apply {
                    moveTo(cx - 5, cy - 9); lineTo(cx - 5, cy + 7); lineTo(cx - 1, cy + 3.2)
                    lineTo(cx + 2, cy + 9.5); lineTo(cx + 4.8, cy + 8.2); lineTo(cx + 1.9, cy + 2)
                    lineTo(cx + 7, cy + 2); closePath()
                }
                if (look.pointer && !look.pointerIdle) g.fill(arrow)
                g.draw(arrow)
            }
            ChooserButton.TIMER -> {
                g.draw(Ellipse2D.Double(cx - 8.5, cy - 7, 17.0, 17.0))
                g.draw(Line2D.Double(cx, cy + 1.5, cx, cy - 3))
                g.draw(Line2D.Double(cx - 3, cy - 10, cx + 3, cy - 10))
            }
            ChooserButton.CLOSE -> {
                g.draw(Line2D.Double(cx - 5.5, cy - 5.5, cx + 5.5, cy + 5.5))
                g.draw(Line2D.Double(cx + 5.5, cy - 5.5, cx - 5.5, cy + 5.5))
            }
            ChooserButton.TAKE -> {}
        }
        g.stroke = plain
    }

    override fun close() {
        made.values.forEach(GpuTexture::close)
        made.clear()
    }

}

/**
 * A short text in a pill (a button's name with its key in a dimmer colour; the size of the
 * part chosen), as a picture; the last few kept.
 */
internal class Label(private val renderer: Renderer) : AutoCloseable {

    private val made = object : LinkedHashMap<Triple<String, String, Double>, GpuTexture>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, Double>, GpuTexture>): Boolean {
            if (size <= 12) return false
            eldest.value.close()
            return true
        }
    }

    /** Its size in units for [text] and [hint]. */
    fun size(text: String, hint: String = "", style: ScreenshotStyle): Pair<Int, Int> {
        val font = labelFont(style)
        val w = textWidth(text, font) + if (hint.isEmpty()) 0 else style.labelPadding * 3 / 4 + textWidth(hint, font)
        return (w + 2 * style.labelPadding) to Math.round(style.fontSize + 14)
    }

    fun texture(text: String, hint: String, scale: Double, style: ScreenshotStyle): GpuTexture = made.getOrPut(Triple(text, hint, scale)) {
        val (width, height) = size(text, hint, style)
        picture(Math.round(width * scale).toInt(), Math.round(height * scale).toInt(), renderer) { g ->
            g.scale(scale, scale)
            g.color = style.background.awt()
            g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 2.0 * style.labelRadius, 2.0 * style.labelRadius))
            g.color = style.border.awt()
            g.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, 2.0 * style.labelRadius, 2.0 * style.labelRadius))
            g.font = labelFont(style)
            val baseline = ((height + g.fontMetrics.ascent - g.fontMetrics.descent) / 2.0).toFloat()
            g.color = style.text.awt()
            g.drawString(text, style.labelPadding.toFloat(), baseline)
            if (hint.isNotEmpty()) {
                g.color = style.dim.awt()
                g.drawString(hint, (style.labelPadding + g.fontMetrics.stringWidth(text) + style.labelPadding * 3 / 4).toFloat(), baseline)
            }
        }
    }

    override fun close() {
        made.values.forEach(GpuTexture::close)
        made.clear()
    }
}

private fun labelFont(style: ScreenshotStyle) = Font(style.font, Font.PLAIN, 1).deriveFont(style.fontSize)

private fun captureFont(style: ScreenshotStyle) = Font(style.font, Font.BOLD, 1).deriveFont(style.captureFontSize)

private val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply {
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
}

// How wide [text] is in [font] (units, rounded up).
private fun textWidth(text: String, font: Font): Int = synchronized(measure) { Math.ceil(font.getStringBounds(text, measure.fontRenderContext).width).toInt() }

// A picture [w] x [h] pixels drawn by [paint] (smooth lines and text), on the GPU.
internal fun picture(w: Int, h: Int, renderer: Renderer, paint: (Graphics2D) -> Unit): GpuTexture {
    val image = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB_PRE)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        paint(g)
    } finally {
        g.dispose()
    }
    val texture = renderer.createTexture(image.width, image.height)
    Arena.ofConfined().use { arena ->
        val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        texture.upload(arena.allocateFrom(ValueLayout.JAVA_INT, *data), 0, image.height)
    }
    return texture
}

private fun centred(g: Graphics2D, text: String, cx: Double, baseline: Double) {
    g.drawString(text, (cx - g.fontMetrics.stringWidth(text) / 2.0).toFloat(), baseline.toFloat())
}

/** Java's 2D graphics and the font loaded once beforehand (the first use takes a moment). */
internal fun warmUpDrawing(style: ScreenshotStyle) {
    Thread({
        runCatching {
            val g = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = captureFont(style)
            g.drawString("Aa", 0f, 8f)
            g.font = labelFont(style)
            g.drawString("Aa", 0f, 8f)
            g.fill(RoundRectangle2D.Double(0.0, 0.0, 8.0, 8.0, 4.0, 4.0))
            g.dispose()
        }
    }, "caulis-screenshot-warmup").apply { isDaemon = true }.start()
}
