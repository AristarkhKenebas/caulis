package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * The on-screen display of a laptop's keys: a pill near the bottom of the focused monitor
 * with what changed ("Volume", "Touchpad off") and, for a level, a bar — shown again by the
 * next key at once, gone [seconds] after the last ([Desktop.showLevel]). It can also show a
 * short list with one chosen (the display modes: [Desktop.showChoices]).
 */
internal class Osd(private val renderer: Renderer) {

    /**
     * What it says: a title, a level 0..1 (or null: no bar) and whether that level is off
     * (muted) — or [choices] with the one [chosen] marked.
     */
    data class Look(val title: String, val level: Double?, val off: Boolean, val choices: List<String> = emptyList(), val chosen: Int = -1)

    var look: Look? = null
        private set
    private var since = 0L
    private var texture: Pair<Any, GpuTexture>? = null

    /** How long it stays after the last change. */
    var seconds = 1.5

    fun show(look: Look) {
        this.look = look
        since = System.nanoTime()
    }

    /** True while it is on screen or fading (frames go on). */
    fun animating(now: Long): Boolean {
        if (look == null) return false
        if (now - since > ((seconds * 1000).toLong() + FADE_MILLIS) * 1_000_000) {
            look = null
            texture?.second?.close()
            texture = null
            return true // (one more frame without it)
        }
        return true
    }

    fun items(monitor: Monitor, now: Long): List<DrawItem> {
        val look = look ?: return emptyList()
        val area = monitor.usableArea
        val width = 280
        val height = (if (look.level != null) 72 else 48) + look.choices.size * ROW
        val rect = Rect(area.x + (area.width - width) / 2, area.bottom - height - 80, width, height)
        val shown = (now - since) / 1_000_000.0
        val alpha = when {
            shown < FADE_MILLIS -> shown / FADE_MILLIS
            shown > seconds * 1000 -> 1 - (shown - seconds * 1000) / FADE_MILLIS
            else -> 1.0
        }.coerceIn(0.0, 1.0).toFloat()
        return listOf(DrawItem.Image(OSD_KEY, texture(look, monitor, rect), rect, rect, 14f, alpha))
    }

    private fun texture(look: Look, monitor: Monitor, rect: Rect): GpuTexture {
        val scale = monitor.scale
        val w = Math.round(rect.width * scale).toInt()
        val h = Math.round(rect.height * scale).toInt()
        val key = listOf(look, w, h)
        texture?.let { (shown, t) -> if (shown == key) return t else t.close() }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = java.awt.Color(30, 32, 38, 235)
            g.fill(RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), h.toDouble(), 28 * scale, 28 * scale))
            g.color = java.awt.Color(236, 238, 242)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((15 * scale).toFloat())
            val metrics = g.fontMetrics
            val title = look.title
            g.drawString(title, ((w - metrics.stringWidth(title)) / 2).toFloat(), (22 * scale + metrics.ascent / 2.0).toFloat())
            look.level?.let { level ->
                val barX = 24 * scale
                val barY = 46 * scale
                val barW = w - 2 * barX
                val barH = 8 * scale
                g.color = java.awt.Color(255, 255, 255, 50)
                g.fill(RoundRectangle2D.Double(barX, barY, barW, barH, barH, barH))
                g.color = if (look.off) java.awt.Color(140, 140, 150) else java.awt.Color(137, 180, 250)
                g.fill(RoundRectangle2D.Double(barX, barY, barW * level.coerceIn(0.0, 1.0), barH, barH, barH))
                g.stroke = BasicStroke(1f)
            }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont((14 * scale).toFloat())
            for ((i, choice) in look.choices.withIndex()) {
                val top = (44 + i * ROW) * scale
                if (i == look.chosen) {
                    g.color = java.awt.Color(137, 180, 250, 70)
                    g.fill(RoundRectangle2D.Double(12 * scale, top, w - 24 * scale, (ROW - 4) * scale, 14 * scale, 14 * scale))
                }
                g.color = if (i == look.chosen) java.awt.Color(255, 255, 255) else java.awt.Color(200, 204, 212)
                g.drawString(choice, (28 * scale).toFloat(), (top + (ROW - 4) * scale / 2 + g.fontMetrics.ascent / 2.0 - 2 * scale).toFloat())
            }
        } finally {
            g.dispose()
        }
        val made = renderer.createTexture(w, h)
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            made.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *data), 0, h)
        }
        texture = key to made
        return made
    }

    fun close() {
        texture?.second?.close()
        texture = null
    }

    private companion object {
        const val FADE_MILLIS = 150.0
        const val ROW = 34
        const val OSD_KEY = "the on-screen display"
    }
}
