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

/** The chooser's buttons. */
internal enum class Button(val width: Int) {
    REGION(50), WINDOW(50), SCREEN(50), WINDOWS(50), SELECTION(50), POINTER(50), TIMER(50), TAKE(112), CLOSE(50);

    /** One of the kinds (the group on the left). */
    val isKind: Boolean get() = this == REGION || this == WINDOW || this == SCREEN || this == WINDOWS
}

/**
 * The chooser's toolbar: the kinds (in a group of their own), how a part is chosen, the
 * pointer, the timer, Capture and close — pictures only, their names in a tip above the one
 * under the pointer. Drawn with Java's 2D graphics; each look is kept, so going over the
 * buttons only picks a picture made before.
 */
internal class Toolbar(private val renderer: Renderer, val buttons: List<Button>) : AutoCloseable {

    /** What it shows. */
    data class Look(
        val kind: ShotKind,
        val selection: Screenshots.Selection,
        val pointer: Boolean,
        val delay: Int,
        val hovered: Button?,
        val scale: Double,
        val style: ScreenshotStyle,
    )

    // The looks made (a few: the buttons gone over), the oldest let go.
    private val made = object : LinkedHashMap<Look, GpuTexture>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Look, GpuTexture>): Boolean {
            if (size <= 16) return false
            eldest.value.close()
            return true
        }
    }

    /** Where each button is, from the toolbar's left edge (units). */
    val places: Map<Button, IntRange> = buildMap {
        var x = PAD
        var last: Button? = null
        for (button in buttons) {
            // A gap between the groups: the kinds, the options, Capture.
            if (last != null && (last.isKind != button.isKind || button == Button.TAKE)) x += GAP
            put(button, x until x + button.width)
            x += button.width
            last = button
        }
    }

    /** Its size in desktop units. */
    val width = (places.values.maxOfOrNull { it.last + 1 } ?: 0) + PAD
    val height = HEIGHT

    /** The button at [x], [y] of a toolbar at [at] (desktop units), or null. */
    fun buttonAt(at: dev.caulis.geometry.Rect, x: Double, y: Double): Button? {
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
        val tile = 2.0 * TILE_RADIUS
        for ((button, range) in places) {
            val x = range.first.toDouble()
            val w = button.width.toDouble()
            val cx = x + w / 2
            val cy = height / 2.0
            val box = RoundRectangle2D.Double(x + 3, INSET, w - 6, height - 2 * INSET, tile, tile)
            val hovered = look.hovered == button
            if (button == Button.TAKE) {
                g.color = st.accent.awt()
                g.fill(box)
                if (hovered) {
                    g.color = java.awt.Color(1f, 1f, 1f, 0.14f)
                    g.fill(box)
                }
                g.color = st.onAccent.awt()
                g.font = Font(st.font, Font.BOLD, 13)
                centred(g, st.word("Capture"), cx, cy + 4.5)
                continue
            }
            val on = when (button) {
                Button.REGION -> look.kind == ShotKind.REGION
                Button.WINDOW -> look.kind == ShotKind.WINDOW
                Button.SCREEN -> look.kind == ShotKind.SCREEN
                Button.WINDOWS -> look.kind == ShotKind.WINDOWS
                Button.POINTER -> look.pointer
                Button.TIMER -> look.delay > 0
                else -> false
            }
            // How a part is chosen means something for a part only.
            val idle = button == Button.SELECTION && look.kind != ShotKind.REGION
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
            if (button == Button.TIMER && look.delay > 0) {
                g.font = Font(st.font, Font.BOLD, 10)
                val text = look.delay.toString()
                val tw = g.fontMetrics.stringWidth(text).toDouble()
                val bw = maxOf(14.0, tw + 6)
                g.color = st.onAccent.awt()
                g.fill(RoundRectangle2D.Double(x + w - 3 - bw - 2, INSET + 2, bw, 13.0, 8.0, 8.0))
                g.color = st.accent.awt()
                g.drawString(text, (x + w - 5 - bw + (bw - tw) / 2).toFloat(), (INSET + 12.5).toFloat())
            }
        }
    }

    // Line pictures about 22 units across, centred on [cx], [cy].
    private fun icon(g: Graphics2D, button: Button, cx: Double, cy: Double, look: Look) {
        val dashed = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3.2f), 0f)
        val plain = g.stroke
        when (button) {
            Button.REGION -> {
                g.stroke = dashed
                g.draw(RoundRectangle2D.Double(cx - 10, cy - 7.5, 20.0, 15.0, 4.0, 4.0))
                g.stroke = plain
                g.draw(Line2D.Double(cx - 11.5, cy - 9.5, cx - 11.5, cy - 4.5)); g.draw(Line2D.Double(cx - 11.5, cy - 9.5, cx - 6.5, cy - 9.5))
                g.draw(Line2D.Double(cx + 11.5, cy + 9.5, cx + 11.5, cy + 4.5)); g.draw(Line2D.Double(cx + 11.5, cy + 9.5, cx + 6.5, cy + 9.5))
            }
            Button.WINDOW -> {
                g.draw(RoundRectangle2D.Double(cx - 10, cy - 8, 20.0, 16.0, 5.0, 5.0))
                g.fill(Ellipse2D.Double(cx - 7, cy - 5.2, 2.4, 2.4))
                g.fill(Ellipse2D.Double(cx - 3.4, cy - 5.2, 2.4, 2.4))
                g.draw(Line2D.Double(cx - 10, cy - 1.5, cx + 10, cy - 1.5))
            }
            Button.SCREEN -> {
                g.draw(RoundRectangle2D.Double(cx - 11, cy - 9, 22.0, 14.5, 4.0, 4.0))
                g.draw(Line2D.Double(cx - 4.5, cy + 9, cx + 4.5, cy + 9))
                g.draw(Line2D.Double(cx, cy + 5.5, cx, cy + 9))
            }
            Button.WINDOWS -> {
                // Three windows, whole, inside a frame chosen around them.
                g.stroke = dashed
                g.draw(RoundRectangle2D.Double(cx - 12, cy - 10, 24.0, 20.0, 5.0, 5.0))
                g.stroke = plain
                g.fill(RoundRectangle2D.Double(cx - 8, cy - 6, 8.5, 12.0, 3.0, 3.0))
                g.fill(RoundRectangle2D.Double(cx + 2, cy - 6, 6.0, 5.2, 2.5, 2.5))
                g.fill(RoundRectangle2D.Double(cx + 2, cy + 0.8, 6.0, 5.2, 2.5, 2.5))
            }
            Button.SELECTION -> if (look.selection == Screenshots.Selection.ADJUST) {
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
            Button.POINTER -> {
                val arrow = Path2D.Double().apply {
                    moveTo(cx - 5, cy - 9); lineTo(cx - 5, cy + 7); lineTo(cx - 1, cy + 3.2)
                    lineTo(cx + 2, cy + 9.5); lineTo(cx + 4.8, cy + 8.2); lineTo(cx + 1.9, cy + 2)
                    lineTo(cx + 7, cy + 2); closePath()
                }
                if (look.pointer) g.fill(arrow)
                g.draw(arrow)
            }
            Button.TIMER -> {
                g.draw(Ellipse2D.Double(cx - 8.5, cy - 7, 17.0, 17.0))
                g.draw(Line2D.Double(cx, cy + 1.5, cx, cy - 3))
                g.draw(Line2D.Double(cx - 3, cy - 10, cx + 3, cy - 10))
            }
            Button.CLOSE -> {
                g.draw(Line2D.Double(cx - 5.5, cy - 5.5, cx + 5.5, cy + 5.5))
                g.draw(Line2D.Double(cx + 5.5, cy - 5.5, cx - 5.5, cy + 5.5))
            }
            Button.TAKE -> {}
        }
        g.stroke = plain
    }

    override fun close() {
        made.values.forEach(GpuTexture::close)
        made.clear()
    }

    private companion object {
        const val HEIGHT = 58
        const val PAD = 7
        const val GAP = 12
        const val INSET = 7.0
        const val TILE_RADIUS = 11.0
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
        val metrics = metrics(style)
        val w = metrics.stringWidth(text) + if (hint.isEmpty()) 0 else HINT_GAP + metrics.stringWidth(hint)
        return (w + 2 * PAD_X) to HEIGHT
    }

    fun texture(text: String, hint: String, scale: Double, style: ScreenshotStyle): GpuTexture = made.getOrPut(Triple(text, hint, scale)) {
        val (width, height) = size(text, hint, style)
        picture(Math.round(width * scale).toInt(), Math.round(height * scale).toInt(), renderer) { g ->
            g.scale(scale, scale)
            g.color = style.background.awt()
            g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 2.0 * RADIUS, 2.0 * RADIUS))
            g.color = style.border.awt()
            g.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, 2.0 * RADIUS, 2.0 * RADIUS))
            g.font = Font(style.font, Font.PLAIN, FONT)
            g.color = style.text.awt()
            g.drawString(text, PAD_X.toFloat(), 17.5f)
            if (hint.isNotEmpty()) {
                g.color = style.text.awt(0.5f)
                g.drawString(hint, (PAD_X + g.fontMetrics.stringWidth(text) + HINT_GAP).toFloat(), 17.5f)
            }
        }
    }

    override fun close() {
        made.values.forEach(GpuTexture::close)
        made.clear()
    }

    private companion object {
        const val HEIGHT = 26
        const val RADIUS = 8.0
        const val PAD_X = 11
        const val HINT_GAP = 8
        const val FONT = 12
        private val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

        fun metrics(style: ScreenshotStyle) = synchronized(measure) { measure.getFontMetrics(Font(style.font, Font.PLAIN, FONT)) }
    }
}

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
            g.font = Font(style.font, Font.BOLD, 13)
            g.drawString("Aa", 0f, 8f)
            g.font = Font(style.font, Font.PLAIN, 12)
            g.drawString("Aa", 0f, 8f)
            g.fill(RoundRectangle2D.Double(0.0, 0.0, 8.0, 8.0, 4.0, 4.0))
            g.dispose()
        }
    }, "caulis-screenshot-warmup").apply { isDaemon = true }.start()
}
