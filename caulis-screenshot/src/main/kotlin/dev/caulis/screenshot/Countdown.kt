package dev.caulis.screenshot

import dev.caulis.core.Desktop
import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Widget
import dev.caulis.geometry.Rect

/**
 * The seconds left before a screenshot is taken: a pill at the bottom of the focused monitor,
 * in the chooser's look. A widget of the screenshots' own, taken away before the picture is
 * taken — so it is never in it (the on-screen display would still be fading out).
 */
internal class Countdown(private val desktop: Desktop, private val style: ScreenshotStyle) : Widget, AutoCloseable {

    private val label = Label(desktop.renderer)
    private val monitor: Monitor = desktop.focusedMonitor

    /** Seconds left. */
    var left = 0
        set(value) {
            field = value
            desktop.requestRedraw()
        }

    private fun text() = style.words.countdown.with(left)

    override fun area(monitor: Monitor): Rect? {
        if (monitor !== this.monitor || left <= 0) return null
        val (w, h) = label.size(text(), "", style)
        val usable = monitor.usableArea
        return Rect(usable.x + (usable.width - w) / 2, usable.bottom - h - style.toolbarMargin - style.toolbarHeight / 2, w, h)
    }

    override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) {
        canvas.image(label.texture(text(), "", monitor.scale, style), area, radius = style.labelRadius)
    }

    override fun close() = label.close()
}
