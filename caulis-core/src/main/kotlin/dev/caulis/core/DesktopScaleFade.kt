package dev.caulis.core

import dev.caulis.render.GpuTexture

/**
 * A scale change: the last frame of a monitor melts into the new layout.
 */
internal class DesktopScaleFade(private val desktop: Desktop) {

    // The picture each monitor showed before its scale changed, and when it began to fade.
    // A monitor's picture from before a scale change: its texture, since when, and what to
    // close when it is over (the picture it was drawn into).
    internal class Fade(val texture: GpuTexture, val since: Long, val owner: AutoCloseable)

    internal val fades = HashMap<Monitor, Fade>()

    internal fun dropFade(monitor: Monitor) {
        fades.remove(monitor)?.owner?.close()
        fadeAlphas -= monitor
    }

    internal val fadeAlphas = HashMap<Monitor, Float>()

    // Takes the picture on [monitor] now (as a screenshot does); it fades out over the new
    // scale, while programs draw themselves anew underneath. Without animations: no fade.
    internal fun fadeFromNow(monitor: Monitor) {
        if (!desktop.animation("fadeScale", desktop.animations.scaleChangeMillis).on || desktop.views.none { it.monitor === monitor }) return
        val width = monitor.pixelWidth
        val height = monitor.pixelHeight
        if (width <= 0 || height <= 0) return
        dropFade(monitor)
        try {
            // Drawn as on screen, without our cursor (it would fade out as a ghost) and without
            // telling programs anything (their frames are on the real screen).
            val target = desktop.renderer.createTarget(width, height)
            try {
                desktop.renderer.begin(target, desktop.backgroundColor.red, desktop.backgroundColor.green, desktop.backgroundColor.blue)
                desktop.frames.draw(desktop.capturing.captureItems(cursor = false, hide = false), monitor.area, monitor::toPixels, monitor.scale, monitor)
                desktop.renderer.end()
            } catch (e: Throwable) {
                target.close()
                throw e
            }
            val texture = target.texture
            if (texture != null) {
                fades[monitor] = Fade(texture, System.nanoTime(), target) // read where it is: no copy
            } else {
                target.use {
                    java.lang.foreign.Arena.ofConfined().use { arena ->
                        val pixels = arena.allocate(width.toLong() * height * 4)
                        target.readPixels(pixels, width * 4)
                        val copy = desktop.renderer.createTexture(width, height)
                        copy.upload(pixels, 0, height)
                        fades[monitor] = Fade(copy, System.nanoTime(), copy)
                    }
                }
            }
            fadeAlphas[monitor] = 1f
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.renderLog.warn("The picture before the scale change could not be kept (it changes without fading)", e)
        }
        desktop.version++
        desktop.onChange()
    }

    // True while some monitor fades: each frame is more of the new picture.
    internal fun fading(): Boolean {
        val now = System.nanoTime()
        val melt = desktop.animation("fadeScale", desktop.animations.scaleChangeMillis)
        for ((monitor, fade) in fades.toList()) {
            val t = (now - fade.since) / 1_000_000.0 / melt.effectiveMillis.coerceAtLeast(1)
            if (t >= 1.0 || !melt.on) {
                dropFade(monitor)
                desktop.version++
            } else {
                fadeAlphas[monitor] = (1 - melt.curve.safeAt(t)).toFloat() // (smooth at both ends, usually)
            }
        }
        return fades.isNotEmpty()
    }
}
