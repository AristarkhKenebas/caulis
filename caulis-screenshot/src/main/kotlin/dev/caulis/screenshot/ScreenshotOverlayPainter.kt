package dev.caulis.screenshot

import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.geometry.Rect
import dev.caulis.render.Paint

/**
 * Drawing the screenshot tool: frozen screens, windows, what is chosen, the toolbar.
 */
internal class ScreenshotOverlayPainter(private val screenshotOverlay: ScreenshotOverlay) {

    // 0..1 while coming in (along the curve of the desktop's animation "screenshot"), then 1; off: 1 at once.
    private fun shown(): Float {
        val coming = screenshotOverlay.desktop.animation("screenshot", screenshotOverlay.behaviour.appearMillis)
        if (!coming.on) return 1f
        val t = ((System.nanoTime() - screenshotOverlay.openedAt) / (coming.millis * 1e6)).coerceIn(0.0, 1.0)
        if (t < 1.0 && !screenshotOverlay.ticking) {
            screenshotOverlay.ticking = true
            screenshotOverlay.caulis.after(8) {
                screenshotOverlay.ticking = false
                screenshotOverlay.desktop.redrawOverlay()
            }
        }
        return try {
            coming.at(t).toFloat()
        } catch (e: Exception) {
            t.toFloat()
        }
    }

    fun draw(monitor: Monitor, canvas: OverlayCanvas) {
        val picture = screenshotOverlay.frozen[monitor] ?: return // (plugged in since: shown as it is)
        val area = monitor.area
        val appear = shown()
        val dim = screenshotOverlay.style.shade * appear
        val accent = screenshotOverlay.style.accent
        canvas.image(picture.texture, area)
        when (screenshotOverlay.kind) {
            ShotKind.REGION -> {
                val part = screenshotOverlay.region?.takeIf { screenshotOverlay.regionOn === monitor }?.intersect(area)
                if (part == null) {
                    canvas.fill(area, 0f, 0f, 0f, dim)
                } else {
                    dimAround(canvas, area, part, dim)
                    canvas.border(screenshotOverlay.grow(part, 2), 2f, Paint.Solid(accent), alpha = 0.95f)
                    if (screenshotOverlay.selection == Screenshots.Selection.ADJUST && (screenshotOverlay.drag == ScreenshotOverlay.Drag.NONE || screenshotOverlay.drag == ScreenshotOverlay.Drag.RESIZE)) handles(canvas, part)
                    val px = picture.toPixels(part)
                    sizeLabel(canvas, monitor, area, part, "${px.width} × ${px.height}")
                }
            }
            ShotKind.WINDOW -> {
                val shownAt = screenshotOverlay.window?.let { w -> screenshotOverlay.windowRect(w, monitor) }?.intersect(area)
                if (shownAt == null) {
                    canvas.fill(area, 0f, 0f, 0f, dim)
                } else {
                    dimAround(canvas, area, shownAt, dim)
                    canvas.border(screenshotOverlay.grow(shownAt, 2), 3f, Paint.Solid(accent), radius = 6f, alpha = 0.9f)
                }
            }
            ShotKind.SCREEN -> {
                if (monitor === screenshotOverlay.screen) canvas.border(area, 4f, Paint.Solid(accent), alpha = 0.9f) else canvas.fill(area, 0f, 0f, 0f, dim)
            }
            ShotKind.WINDOWS -> drawWindows(canvas, monitor, picture, area, dim)
        }
        if (monitor === screenshotOverlay.home) drawToolbar(canvas, monitor, appear)
    }

    private fun drawWindows(canvas: OverlayCanvas, monitor: Monitor, picture: Frozen, area: Rect, dim: Float) {
        val accent = screenshotOverlay.style.accent
        canvas.fill(area, 0f, 0f, 0f, dim)
        if (monitor !== screenshotOverlay.regionOn && screenshotOverlay.picked.isNotEmpty()) return
        val list = screenshotOverlay.windows[monitor].orEmpty()
        val chosen = screenshotOverlay.chosenNow()
        val under = screenshotOverlay.window?.takeIf { screenshotOverlay.drag == ScreenshotOverlay.Drag.NONE && screenshotOverlay.hovered == null && it !in chosen }
        // The windows chosen bright again (as they are), the one under the pointer a little.
        for ((w, r) in list) {
            val clip = r.intersect(area) ?: continue
            when {
                w in chosen -> canvas.imagePart(picture.texture, area, clip)
                w === under -> canvas.imagePart(picture.texture, area, clip, alpha = 0.55f)
            }
        }
        for ((w, r) in list) {
            if (w in chosen) canvas.border(screenshotOverlay.grow(r, 2), 2.5f, Paint.Solid(accent), radius = screenshotOverlay.radiusOf(w, r, monitor) + 2f, alpha = 0.95f)
            else if (w === under) canvas.border(screenshotOverlay.grow(r, 1), 1.5f, Paint.Solid(screenshotOverlay.style.text), radius = screenshotOverlay.radiusOf(w, r, monitor) + 1f, alpha = 0.7f)
        }
        screenshotOverlay.lasso?.let { l ->
            canvas.fill(l, accent.red, accent.green, accent.blue, 0.12f)
            canvas.border(l, 1.5f, Paint.Solid(accent), alpha = 0.9f)
        }
        // All of them: the picture's edges, and its size.
        val pickedHere = list.filter { it.first in chosen }
        if (pickedHere.isNotEmpty()) {
            val bounds = pickedHere.map { it.second }.reduce(Rect::union)
            if (pickedHere.size > 1) canvas.border(screenshotOverlay.grow(bounds, 7), 1f, Paint.Solid(screenshotOverlay.style.text), radius = 10f, alpha = 0.45f)
            val size = screenshotOverlay.shots.windowsSize(monitor, pickedHere)
            val count = pickedHere.size
            val text = (if (count == 1) screenshotOverlay.words.oneWindow else screenshotOverlay.words.manyWindows.with(count)) +
                (size?.let { "  ·  ${it.first} × ${it.second}" } ?: "")
            sizeLabel(canvas, monitor, area, screenshotOverlay.grow(bounds, 7), text)
        }
    }

    private fun drawToolbar(canvas: OverlayCanvas, monitor: Monitor, appear: Float) {
        val at = screenshotOverlay.toolbarRect().let { r -> Rect(r.x, r.y + Math.round((1 - appear) * screenshotOverlay.behaviour.rise), r.width, r.height) }
        val radius = screenshotOverlay.style.cornerRadius.coerceAtMost(screenshotOverlay.toolbar.height / 2f)
        canvas.shadow(at, screenshotOverlay.style.shadowSize, Paint.Solid(screenshotOverlay.style.shadow), radius = radius, alpha = appear)
        val look = Toolbar.Look(screenshotOverlay.kind, screenshotOverlay.selection, screenshotOverlay.pointer, screenshotOverlay.delay, screenshotOverlay.hovered, monitor.scale, screenshotOverlay.style, pointerIdle = !pointerApplies(), clear = screenshotOverlay.clearShown())
        canvas.image(screenshotOverlay.toolbar.texture(look), at, alpha = appear, radius = radius)
        // The name of the button under the pointer (and its key) above it.
        val button = screenshotOverlay.hovered ?: return
        val range = screenshotOverlay.toolbar.places(screenshotOverlay.clearShown())[button] ?: return
        val (text, key) = tipOf(button)
        val (w, h) = screenshotOverlay.tip.size(text, key, screenshotOverlay.style)
        val cx = at.x + (range.first + range.last + 1) / 2
        val tx = (cx - w / 2).coerceIn(screenshotOverlay.home.area.x + 4, screenshotOverlay.home.area.right - w - 4)
        canvas.image(screenshotOverlay.tip.texture(text, key, monitor.scale, screenshotOverlay.style), Rect(tx, at.y - h - 8, w, h), alpha = appear, radius = screenshotOverlay.style.labelRadius)
    }

    // A button's name, and the key that does the same (as the layout names it).
    private fun tipOf(button: ChooserButton): Pair<String, String> {
        fun key(list: List<String>) = list.firstOrNull()?.let(screenshotOverlay.words::keyName) ?: ""
        return when (button) {
            ChooserButton.REGION -> screenshotOverlay.words.area to key(screenshotOverlay.keys.area)
            ChooserButton.WINDOW -> screenshotOverlay.words.window to key(screenshotOverlay.keys.window)
            ChooserButton.SCREEN -> screenshotOverlay.words.screen to key(screenshotOverlay.keys.screen)
            ChooserButton.WINDOWS -> screenshotOverlay.words.windows to key(screenshotOverlay.keys.windows)
            ChooserButton.SELECTION -> (if (screenshotOverlay.selection == Screenshots.Selection.ADJUST) screenshotOverlay.words.adjustArea else screenshotOverlay.words.newArea) to key(screenshotOverlay.keys.selection)
            // (Escape clears it first, with escapeClearsSelection.)
            ChooserButton.CLEAR -> screenshotOverlay.words.clearSelection to (if (screenshotOverlay.behaviour.escapeClearsSelection) key(screenshotOverlay.keys.close) else "")
            ChooserButton.POINTER -> (if (!pointerApplies()) screenshotOverlay.words.pointerNotHere else if (screenshotOverlay.pointer) screenshotOverlay.words.pointerIn else screenshotOverlay.words.pointerOut) to key(screenshotOverlay.keys.pointer)
            ChooserButton.TIMER -> (if (screenshotOverlay.delay > 0) screenshotOverlay.words.timer.with(screenshotOverlay.delay) else screenshotOverlay.words.timerOff) to key(screenshotOverlay.keys.timer)
            ChooserButton.TAKE -> screenshotOverlay.words.capture to key(screenshotOverlay.keys.capture)
            ChooserButton.CLOSE -> screenshotOverlay.words.close to key(screenshotOverlay.keys.close)
        }
    }

    // True if the pointer can be in what would be taken now: not in a window taken alone, nor
    // in a picture put together from windows.
    private fun pointerApplies(): Boolean = when (screenshotOverlay.kind) {
        ShotKind.SCREEN -> true
        ShotKind.WINDOW, ShotKind.WINDOWS -> false
        ShotKind.REGION -> screenshotOverlay.delay > 0 || screenshotOverlay.region?.let { screenshotOverlay.shots.sharpThere(screenshotOverlay.regionOn, it, screenshotOverlay.windows[screenshotOverlay.regionOn].orEmpty()) } != true
    }

    // A label below [part] (above it when there is no room): its size.
    private fun sizeLabel(canvas: OverlayCanvas, monitor: Monitor, area: Rect, part: Rect, text: String) {
        val (w, h) = screenshotOverlay.label.size(text, "", screenshotOverlay.style)
        val below = part.bottom + 8
        val labelY = if (below + h <= area.bottom && (monitor !== screenshotOverlay.home || below + h <= screenshotOverlay.toolbarRect().y - 8)) below else (part.y - h - 8).coerceAtLeast(area.y + 4)
        val labelX = part.x.coerceIn(area.x + 4, (area.right - w - 4).coerceAtLeast(area.x + 4))
        canvas.image(screenshotOverlay.label.texture(text, "", monitor.scale, screenshotOverlay.style), Rect(labelX, labelY, w, h), radius = screenshotOverlay.style.labelRadius)
    }

    private fun dimAround(canvas: OverlayCanvas, area: Rect, hole: Rect, dim: Float) {
        canvas.fill(Rect(area.x, area.y, area.width, hole.y - area.y), 0f, 0f, 0f, dim)
        canvas.fill(Rect(area.x, hole.bottom, area.width, area.bottom - hole.bottom), 0f, 0f, 0f, dim)
        canvas.fill(Rect(area.x, hole.y, hole.x - area.x, hole.height), 0f, 0f, 0f, dim)
        canvas.fill(Rect(hole.right, hole.y, area.right - hole.right, hole.height), 0f, 0f, 0f, dim)
    }

    private fun handles(canvas: OverlayCanvas, part: Rect) {
        val xs = listOf(part.x, part.x + part.width / 2, part.right)
        val ys = listOf(part.y, part.y + part.height / 2, part.bottom)
        for ((i, hx) in xs.withIndex()) for ((j, hy) in ys.withIndex()) {
            if (i == 1 && j == 1) continue
            val size = screenshotOverlay.style.handleSize
            val dot = Rect(hx - size / 2, hy - size / 2, size, size)
            canvas.roundedFill(screenshotOverlay.grow(dot, 1), size / 2f + 1, screenshotOverlay.style.handleRim)
            canvas.roundedFill(dot, size / 2f, screenshotOverlay.style.handle)
        }
    }
}
