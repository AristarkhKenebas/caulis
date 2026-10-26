package dev.caulis.screenshot

import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/** The chooser's buttons. */
internal enum class Button(val width: Int) {
    REGION(64), WINDOW(64), SCREEN(64), POINTER(64), TIMER(64), TAKE(88), CLOSE(40);

    // A gap with a line before it.
    val gapBefore: Boolean get() = this == POINTER || this == TAKE
}

/**
 * The chooser's toolbar: the kinds, the pointer, the timer, Capture and close, drawn with
 * Java's 2D graphics into a picture (made again only when what it shows changes).
 */
internal class Toolbar(private val renderer: Renderer) : AutoCloseable {

    /** What it shows. */
    data class Look(val kind: ShotKind, val pointer: Boolean, val delay: Int, val hovered: Button?, val scale: Double, val style: ScreenshotStyle)

    private var made: Pair<Look, GpuTexture>? = null

    /** Its size in desktop units. */
    val width = PAD * 2 + Button.entries.sumOf { it.width + if (it.gapBefore) GAP else 0 }
    val height = HEIGHT

    /** Where each button is, from the toolbar's left edge (units). */
    val places: Map<Button, IntRange> = buildMap {
        var x = PAD
        for (button in Button.entries) {
            if (button.gapBefore) x += GAP
            put(button, x until x + button.width)
            x += button.width
        }
    }

    /** The button at [x], [y] of a toolbar at [at] (desktop units), or null. */
    fun buttonAt(at: Rect, x: Double, y: Double): Button? {
        if (y < at.y + 6 || y >= at.bottom - 6) return null
        val local = (x - at.x).toInt()
        return places.entries.firstOrNull { local in it.value }?.key
    }

    /** Its picture for [look]. */
    fun texture(look: Look): GpuTexture {
        made?.let { (shown, texture) -> if (shown == look) return texture else texture.close() }
        val s = look.scale
        val w = Math.round(width * s).toInt()
        val h = Math.round(height * s).toInt()
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.scale(s, s)
            paint(g, look)
        } finally {
            g.dispose()
        }
        val texture = renderer.createTexture(w, h)
        Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(ValueLayout.JAVA_INT, *data), 0, h)
        }
        made = look to texture
        return texture
    }

    private fun paint(g: Graphics2D, look: Look) {
        val st = look.style
        val r = (2.0 * st.cornerRadius).coerceAtMost(height.toDouble())
        g.color = st.background.awt()
        g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), r, r))
        g.color = st.border.awt()
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, r, r))
        val inner = (r - 8).coerceAtLeast(10.0)
        for ((button, range) in places) {
            val x = range.first.toDouble()
            val w = button.width.toDouble()
            if (button.gapBefore) {
                g.color = st.text.awt(0.16f)
                g.fill(java.awt.geom.Rectangle2D.Double(x - GAP / 2.0 - 0.5, 16.0, 1.0, height - 32.0))
            }
            val on = when (button) {
                Button.REGION -> look.kind == ShotKind.REGION
                Button.WINDOW -> look.kind == ShotKind.WINDOW
                Button.SCREEN -> look.kind == ShotKind.SCREEN
                Button.POINTER -> look.pointer
                Button.TIMER -> look.delay > 0
                else -> false
            }
            if (button == Button.TAKE) {
                g.color = if (look.hovered == button) st.accent.awt(0.85f) else st.accent.awt()
                g.fill(RoundRectangle2D.Double(x + 4, 13.0, w - 8, height - 26.0, inner, inner))
                g.color = st.onAccent.awt()
                g.font = Font(st.font, Font.BOLD, 13)
                centred(g, st.word("Capture"), x + w / 2, height / 2.0 + 5)
                continue
            }
            if (on || look.hovered == button) {
                g.color = if (on) st.accent.awt(0.22f) else st.text.awt(0.08f)
                g.fill(RoundRectangle2D.Double(x + 3, 7.0, w - 6, height - 14.0, inner, inner))
            }
            g.color = if (on) st.accent.awt() else st.text.awt()
            g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            icon(g, button, x + w / 2, if (button == Button.CLOSE) height / 2.0 else 25.0, look)
            g.font = Font(st.font, Font.PLAIN, 11)
            g.color = if (on) st.accent.awt() else st.dim.awt()
            val label = when (button) {
                Button.REGION -> st.word("Area")
                Button.WINDOW -> st.word("Window")
                Button.SCREEN -> st.word("Screen")
                Button.POINTER -> st.word("Pointer")
                Button.TIMER -> if (look.delay > 0) st.word("%s s").replace("%s", look.delay.toString()) else st.word("Timer")
                else -> null
            }
            label?.let { centred(g, it, x + w / 2, 50.0) }
        }
    }

    // Simple line icons, 22 units across, centred on [cx], [cy].
    private fun icon(g: Graphics2D, button: Button, cx: Double, cy: Double, look: Look) {
        when (button) {
            Button.REGION -> {
                g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f)
                g.draw(RoundRectangle2D.Double(cx - 11, cy - 8, 22.0, 16.0, 3.0, 3.0))
            }
            Button.WINDOW -> {
                g.draw(RoundRectangle2D.Double(cx - 11, cy - 8, 22.0, 16.0, 3.0, 3.0))
                g.draw(Line2D.Double(cx - 11, cy - 3, cx + 11, cy - 3))
            }
            Button.SCREEN -> {
                g.draw(RoundRectangle2D.Double(cx - 11, cy - 9, 22.0, 14.0, 2.0, 2.0))
                g.draw(Line2D.Double(cx - 5, cy + 9, cx + 5, cy + 9))
                g.draw(Line2D.Double(cx, cy + 5, cx, cy + 9))
            }
            Button.POINTER -> {
                val arrow = Path2D.Double().apply {
                    moveTo(cx - 5, cy - 10); lineTo(cx - 5, cy + 7); lineTo(cx - 1, cy + 3)
                    lineTo(cx + 2, cy + 10); lineTo(cx + 5, cy + 8); lineTo(cx + 2, cy + 2)
                    lineTo(cx + 7, cy + 2); closePath()
                }
                if (look.pointer) g.fill(arrow)
                g.draw(arrow)
            }
            Button.TIMER -> {
                g.draw(Ellipse2D.Double(cx - 9, cy - 8, 18.0, 18.0))
                g.draw(Line2D.Double(cx, cy + 1, cx, cy - 4))
                g.draw(Line2D.Double(cx, cy + 1, cx + 4, cy + 3))
                g.draw(Line2D.Double(cx - 3, cy - 11, cx + 3, cy - 11))
            }
            Button.CLOSE -> {
                g.draw(Line2D.Double(cx - 6, cy - 6, cx + 6, cy + 6))
                g.draw(Line2D.Double(cx + 6, cy - 6, cx - 6, cy + 6))
            }
            Button.TAKE -> {}
        }
    }

    private fun centred(g: Graphics2D, text: String, cx: Double, baseline: Double) {
        g.drawString(text, (cx - g.fontMetrics.stringWidth(text) / 2.0).toFloat(), baseline.toFloat())
    }

    override fun close() {
        made?.second?.close()
        made = null
    }

    private companion object {
        const val HEIGHT = 64
        const val PAD = 8
        const val GAP = 13
    }
}

/** A short text in a dark pill (the size of the part chosen), as a picture; made again when it changes. */
internal class Label(private val renderer: Renderer) : AutoCloseable {

    private var made: Triple<String, Double, GpuTexture>? = null

    /** Its size in units for [text]. */
    fun size(text: String): Pair<Int, Int> = (text.length * 7 + 20) to 24

    fun texture(text: String, scale: Double, style: ScreenshotStyle): GpuTexture {
        made?.let { (shown, s, texture) -> if (shown == text && s == scale) return texture else texture.close() }
        val (width, height) = size(text)
        val w = Math.round(width * scale).toInt()
        val h = Math.round(height * scale).toInt()
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(scale, scale)
            g.color = style.background.awt()
            g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), height.toDouble(), height.toDouble()))
            g.color = style.text.awt()
            g.font = Font(style.font, Font.PLAIN, 12)
            g.drawString(text, ((width - g.fontMetrics.stringWidth(text)) / 2.0).toFloat(), 16.5f)
        } finally {
            g.dispose()
        }
        val texture = renderer.createTexture(w, h)
        Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(ValueLayout.JAVA_INT, *data), 0, h)
        }
        made = Triple(text, scale, texture)
        return texture
    }

    override fun close() {
        made?.third?.close()
        made = null
    }
}
