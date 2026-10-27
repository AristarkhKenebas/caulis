package dev.caulis.core

import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/**
 * Shows another monitor's picture on a screen of any size (a projector mirroring the laptop):
 * the desktop draws [Monitor]'s view into a picture of that monitor's own size, which is then
 * made to fit the screen whole — as large as it goes, black bars where the shapes differ.
 */
class Mirror(private val renderer: Renderer) : AutoCloseable {

    private var picture: GpuTarget? = null
    private var drawnBefore = false

    /** [draw] (a view of [source]) as the backend gets it, for the screen showing the mirror. */
    fun wrap(source: Monitor, draw: (GpuTarget, Int) -> Unit): (GpuTarget, Int) -> Unit = { screen, _ ->
        val width = source.pixelWidth
        val height = source.pixelHeight
        val current = picture?.takeIf { it.width == width && it.height == height } ?: run {
            picture?.close()
            drawnBefore = false
            renderer.createTarget(width, height).also { picture = it }
        }
        // The picture stays ours: only what changed since the last frame is drawn into it.
        val age = if (drawnBefore) 1 else 0
        // Unknown until drawn to the end: a frame that fails half-way is drawn whole next time.
        drawnBefore = false
        draw(current, age)
        drawnBefore = true
        renderer.begin(screen, 0f, 0f, 0f)
        current.texture?.let { texture ->
            val fit = minOf(screen.width.toDouble() / width, screen.height.toDouble() / height)
            val w = Math.round(width * fit).toInt()
            val h = Math.round(height * fit).toInt()
            renderer.drawTexture(texture, (screen.width - w) / 2, (screen.height - h) / 2, w, h, opaque = true)
        }
        renderer.end()
    }

    override fun close() {
        picture?.close()
        picture = null
    }
}
