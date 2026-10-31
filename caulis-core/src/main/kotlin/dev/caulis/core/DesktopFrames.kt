package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.render.Color
import dev.caulis.render.GpuTarget

/**
 * One frame of a screen: whether it is needed, drawing what changed, telling programs it was shown.
 */
internal class DesktopFrames(private val desktop: Desktop) {

    private val BUSY_REPORT_NANOS = 10_000_000_000L

    internal val WALLPAPER_SHADERS = listOf("wallpaper shader")

    // A program drew something: mark those parts as changed where the surface is shown.
    internal fun surfaceCommitted(surface: Surface) {
        val changes = surface.takeChanges()
        if (changes.isNotEmpty()) {
            for (view in desktop.views + desktop.capturing.captureViews) {
                // Everywhere it is drawn, at the size it is drawn (smaller in the overview,
                // stretched while it glides): rounded outwards, so no changed pixel is missed.
                for (item in view.shown) {
                    if (item !is DrawItem.Picture || item.surface !== surface) continue
                    val shownAt = item.rect
                    val sx = if (surface.width > 0) shownAt.width.toDouble() / surface.width else 1.0
                    val sy = if (surface.height > 0) shownAt.height.toDouble() / surface.height else 1.0
                    for (change in changes) {
                        val left = Math.floor(change.x * sx).toInt()
                        val top = Math.floor(change.y * sy).toInt()
                        val right = Math.ceil(change.right * sx).toInt()
                        val bottom = Math.ceil(change.bottom * sy).toInt()
                        view.damage(Rect(shownAt.x + left, shownAt.y + top, right - left, bottom - top))
                    }
                }
            }
        }
        for (listener in desktop.capturing.commitListeners.toList()) listener(surface)
        scheduleHiddenRefresh()
    }

    // After a commit, its role having done its part too. Only pixels changed in a picture on
    // screen: what changed is marked already and the scene stays (a program drawing does not make
    // the whole desktop be worked out again). Anything else: the scene is built anew.
    internal fun commitSettled(surface: Surface) {
        val moreThanPixels = surface.sceneFacts.changedSinceLastTime()
        if (moreThanPixels || desktop.fullRedraw || !desktop.sceneBuilder.drawsNow(surface)) {
            desktop.changed()
            return
        }
        desktop.sceneBuilder.pictureReplaced(surface)
        desktop.renderLog.trace { "$surface: new pixels only, the scene stays" }
        desktop.onChange()
    }

    fun refresh(
        view: DesktopView,
        present: ((GpuTarget, Int) -> Unit) -> Unit,
        force: Boolean = false,
        /**
         * The backend's way to show a program's buffer directly (e.g. `monitor::presentDirect`),
         * or null. Used for a fullscreen window alone on screen; returns false if not possible.
         */
        presentDirect: ((DmabufBuffer) -> Boolean)? = null,
    ): Boolean {
        if (desktop.overlayHost.brokenOverlay != null && desktop.overlayHost.brokenOverlay === desktop.overlay) desktop.closeOverlay()
        // Each asked every time (they move their animations on, and let go of what faded away:
        // none may be skipped because another already said yes).
        val now = System.nanoTime()
        val noticesMoving = desktop.noticeBoard.animating() or desktop.osd.animating(now)
        val fadingNow = desktop.scaleFade.fading()
        val animatingNow = desktop.animator.animating()
        val backdropsChanging = desktop.backdrops.changing()
        val directorAnimating = desktop.director?.let { d -> userCode("Your WindowDirector (animating)", false) { d.animating(now) } } == true
        var directorMoving = directorAnimating
        var movingThing: Any? = null
        for (list in desktop.widgetHost.drawings.values) for (d in list) if (userCode("Your drawing (animating)", false) { d.animating(now) }) { directorMoving = true; movingThing = d }
        for (w in desktop.widgetHost.widgets.toList()) if (userCode("Your widget (animating)", false) { w.animating(now) }) { directorMoving = true; movingThing = w }
        val paintsNow = desktop.looks.paintsMoving && desktop.looks.paintsDue(now)
        if (desktop.animator.glides.isNotEmpty() || desktop.pads.shownPads.any { it.changedAt != null } || desktop.overview.cameraMoving(System.nanoTime()) || fadingNow || noticesMoving || animatingNow || backdropsChanging || paintsNow || desktop.looks.focusChanging || directorMoving) {
            // Windows are gliding (or the overview zooms): the scene changes every frame until done.
            if (desktop.renderLog.level >= 2) {
                val why = listOfNotNull("glides".takeIf { desktop.animator.glides.isNotEmpty() }, "camera".takeIf { desktop.overview.cameraMoving(System.nanoTime()) }, "fading".takeIf { fadingNow },
                    "notices".takeIf { noticesMoving }, "animating".takeIf { animatingNow }, "backdrops".takeIf { backdropsChanging }, "paints".takeIf { paintsNow },
                    "focus".takeIf { desktop.looks.focusChanging }, "director".takeIf { directorAnimating }, movingThing?.let { "drawing or widget $it" })
                desktop.renderLog.trace { "Moving: ${why.joinToString()}" }
                busy.moving(why)
            }
            desktop.version++
            desktop.onChange()
        }
        // (What is under the pointer may fail on a window going away: the frame is drawn all the same.)
        try {
            desktop.pointerInput.pointerOntoWhatIsThere()
        } catch (e: Exception) {
            desktop.log.error("Finding what is under the pointer failed (the frame is drawn anyway)", e)
        }
        desktop.planes.placeHardwareCursor(view)
        // A moving screen shader: every frame anew (and the next one asked for).
        val shaderMoves = desktop.screenShader?.moving == true
        if (shaderMoves) desktop.onChange()
        val draw = force || needsRedraw(view) || shaderMoves
        val direct = if (draw && !force && presentDirect != null) directCandidate(view) else null
        if (direct != null && presentDirect!!(direct)) {
            // Nothing drawn: the program's own picture is on screen.
            view.shownWithoutDrawing()
            takeFeedbacks(view)
        } else if (draw) {
            var drawn = false
            present { target, age ->
                drawn = true
                try {
                    render(view, target, age)
                } catch (e: Throwable) {
                    // Half a frame: the next one is drawn whole, so nothing stale stays on screen.
                    view.damageEverything()
                    throw e
                }
            }
            // Nothing reached the screen (it is off, or another terminal is shown): what changed
            // stays to be drawn, and the backend's slow heartbeat still gives programs their frames.
            if (!drawn) desktop.renderLog.trace { "${view.monitor.name}: nothing drawn (the screen is dark)" }
        } else {
            // Nothing new to show: whatever programs committed is on screen already.
            takeFeedbacks(view)
            presented(view, System.nanoTime(), refreshNanos = 0, sequence = 0, flags = 0)
        }
        frameShown(view, (System.nanoTime() / 1_000_000).toInt())
        for (listener in desktop.capturing.frameListeners.toList()) listener()
        if (draw && desktop.renderLog.level >= 2) busy.drawn(view.monitor.name)
        return draw
    }

    // What kept the screens being drawn: said every few seconds at the render log's info level
    // (CAULIS_LOG=render=2) — the frames drawn per monitor, and what was moving, most often first.
    // (To find out why a compositor that should be idle keeps a processor busy.)
    internal inner class BusyReport {
        private var since = System.nanoTime()
        private val frames = LinkedHashMap<String, Int>()
        private val reasons = HashMap<String, Int>()

        fun moving(why: List<String>) {
            for (reason in why) reasons.merge(reason, 1, Int::plus)
        }

        fun drawn(monitor: String) {
            frames.merge(monitor, 1, Int::plus)
            val now = System.nanoTime()
            if (now - since < BUSY_REPORT_NANOS) return
            val seconds = (now - since) / 1e9
            desktop.renderLog.info {
                "Frames in the last %.0f s: %s; moving: %s".format(seconds, frames.entries.joinToString { "${it.key} ${it.value}" },
                    reasons.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${it.value})" }.ifEmpty { "nothing (programs' or the pointer's changes)" }) +
                    "; scenes built: ${desktop.sceneBuilder.takeBuilds()}; pictures copied: ${copiedSinceReport()} KB"
            }
            since = now
            frames.clear()
            reasons.clear()
        }
    }

    internal val busy = BusyReport()

    private var copiedAtReport = 0L

    private fun copiedSinceReport(): Long {
        val now = desktop.compositor.bytesCopied
        return ((now - copiedAtReport) / 1024).also { copiedAtReport = now }
    }

    fun presented(view: DesktopView, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) {
        Presentation.presented(view.inFlight, nanos, refreshNanos, sequence, flags)
        view.inFlight.clear()
        if (desktop.lockHost.lockPending && view.showsLock) {
            desktop.lockHost.lockShownOn += view
            desktop.lockHost.confirmLockIfShown()
        }
    }

    fun needsRedraw(view: DesktopView): Boolean {
        compare(view, itemsFor(view))
        return view.hasChanges
    }

    // What [view]'s frames show: all, or all but the cursor while it is on the cursor plane.
    private fun itemsFor(view: DesktopView): List<DrawItem> {
        val all = desktop.sceneBuilder.items()
        if (!view.cursorOnPlane) return all
        if (itemsWithoutCursorOf !== all) {
            itemsWithoutCursor = all.subList(0, desktop.sceneBuilder.itemsCursorStart.coerceAtMost(all.size))
            itemsWithoutCursorOf = all
        }
        return itemsWithoutCursor
    }

    private var itemsWithoutCursor: List<DrawItem> = emptyList()

    private var itemsWithoutCursorOf: List<DrawItem>? = null

    fun render(view: DesktopView, target: GpuTarget, age: Int) {
        val items = itemsFor(view)
        compare(view, items)
        takeFeedbacks(view)
        val monitor = view.monitor
        val bounds = monitor.area
        view.showsLock = desktop.lockHost.lockHidesDesktop
        var parts = view.takeRepaintParts(if (desktop.fullRedraw) 0 else age, bounds) ?: return
        backgroundAsked = 0
        backgroundDrawn = 0
        // Blur reads around its area: a change near it (within its reach, not only under it)
        // changes it too — its whole reach redrawn first, in one part (else a bar's blur stayed
        // stale while a program drew next to it, and flashed when it caught up).
        if (items.any { it is DrawItem.Blur && parts.any { part -> it.reachRect().overlaps(part) } }) {
            parts = withBlurParts(items.filterIsInstance<DrawItem.Blur>().map { it.reachRect() }, parts, bounds)
        }
        // In pixels, one more around each: a fractional scale must not leave a half-drawn edge.
        // Where those overlap, each pixel belongs to one part only (nothing is drawn twice).
        val whole = Rect(0, 0, target.width, target.height)
        val clips = ArrayList<Pair<Rect, Rect>>() // pixels, and the part in units it shows
        for (part in parts) {
            val px = monitor.toPixels(part)
            var pieces = listOfNotNull(Rect(px.x - 1, px.y - 1, px.width + 2, px.height + 2).intersect(whole))
            for ((taken, _) in clips) pieces = pieces.flatMap { it.minus(taken) }
            pieces.forEach { clips += it to part }
        }
        if (clips.isEmpty()) return
        desktop.renderLog.trace { "Frame of ${monitor.name}: redraw ${parts.joinToString()} (${clips.size} parts in pixels) of ${target.width}x${target.height} (buffer age $age), ${items.size} things to draw" }
        val toPixels = monitor::toPixels
        for ((i, clip) in clips.withIndex()) {
            val (pixels, part) = clip
            // (One unit more around the part: what touches its pixels.)
            val near = Rect(part.x - 1, part.y - 1, part.width + 2, part.height + 2)
            if (i == 0) {
                desktop.renderer.begin(target, desktop.backgroundColor.red, desktop.backgroundColor.green, desktop.backgroundColor.blue, clip = pixels)
            } else {
                desktop.renderer.setClip(pixels)
                desktop.renderer.drawColor(pixels.x, pixels.y, pixels.width, pixels.height, desktop.backgroundColor.red, desktop.backgroundColor.green, desktop.backgroundColor.blue)
            }
            draw(items, near, toPixels, monitor.scale, monitor, pixels)
        }
        desktop.renderer.end()
        desktop.renderLog.trace { "Frame of ${monitor.name}: background $backgroundDrawn of $backgroundAsked pixels drawn (the rest under opaque windows)" }
    }

    // In this frame: the background's pixels in the parts redrawn, and those really drawn.
    private var backgroundAsked = 0L

    private var backgroundDrawn = 0L

    // Draws background [item] (at [index] of [items]) within [repaint]: only where nothing opaque
    // is above it (else every window drawing would draw the wallpaper under itself first). Each
    // piece one pixel bigger: a fractional scale must not leave an edge undrawn; the window above
    // is drawn over it. [clip]: the part's pixels. Too many pieces (or fullRedraw): as a whole.
    private fun drawBackground(item: DrawItem.Custom, items: List<DrawItem>, index: Int, repaint: Rect, clip: Rect, r: Rect, toPixels: (Rect) -> Rect) {
        val asked = clip.intersect(r)
        backgroundAsked += asked?.area() ?: 0L
        val whole = item.rect.intersect(repaint)
        val pieces = if (whole == null || desktop.fullRedraw) null else uncoveredBackground(item, items, index)
        // (All of the part shows: one go.)
        if (pieces == null || pieces.any { it.contains(whole!!) }) {
            item.paint(r)
            backgroundDrawn += asked?.area() ?: 0L
            return
        }
        for (uncut in pieces) {
            val piece = uncut.intersect(whole!!) ?: continue
            val px = toPixels(piece)
            val scissor = Rect(px.x - 1, px.y - 1, px.width + 2, px.height + 2).intersect(clip) ?: continue
            desktop.renderer.setClip(scissor)
            item.paint(r)
            backgroundDrawn += scissor.intersect(r)?.area() ?: 0L
        }
        desktop.renderer.setClip(clip)
    }

    // What shows of background [item]: the same until the list changes, and each part of each
    // frame asks. (Null: too many pieces.)
    private fun uncoveredBackground(item: DrawItem.Custom, items: List<DrawItem>, index: Int): List<Rect>? {
        if (uncoveredOf !== items) {
            uncoveredOf = items
            uncoveredKept.clear()
        }
        if (uncoveredKept.containsKey(item)) return uncoveredKept[item]
        return uncovered(listOf(item.rect), items, index, MAX_BACKGROUND_PIECES).also { uncoveredKept[item] = it }
    }

    private var uncoveredOf: List<DrawItem>? = null

    private val uncoveredKept = java.util.IdentityHashMap<DrawItem.Custom, List<Rect>?>()

    // Draws [items] (only those touching [repaint]) into the frame begun; [p] turns desktop
    // units into the target's pixels, at [scale].
    // [clip]: the pixels the frame is cut to now (a part of the monitor), when known.
    internal fun draw(items: List<DrawItem>, repaint: Rect?, p: (Rect) -> Rect, scale: Double, monitor: Monitor? = null, clip: Rect? = null) {
        val s = scale.toFloat()
        for (index in items.indices) {
            val item = items[index]
            if (repaint != null && !item.rect.overlaps(repaint)) continue
            if (monitor != null && item.only != null && item.only !== monitor) continue // (a sliding workspace of another monitor)
            val r = p(item.rect)
            when (item) {
                is DrawItem.Picture -> desktop.renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, item.opaque, item.alpha, item.clip?.let(p), item.radius * s, item.source, item.transform)
                is DrawItem.Color -> desktop.renderer.drawColor(r.x, r.y, r.width, r.height, item.red, item.green, item.blue, item.alpha)
                is DrawItem.Shadow -> {
                    val drawn = desktop.renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, item.paint, p(item.cutOut), item.cutOutRadius * s, item.alpha, item.time)
                    desktop.looks.paintWorks(item.paint, drawn)
                    if (!drawn) desktop.renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, dev.caulis.render.Paint.solid(dev.caulis.render.Color(0f, 0f, 0f, 0.45f * item.alpha)), p(item.cutOut), item.cutOutRadius * s)
                }
                is DrawItem.Border -> {
                    val drawn = desktop.renderer.drawRoundedRect(r, item.radius * s, item.paint, item.width * s, item.alpha, item.time, item.state)
                    desktop.looks.paintWorks(item.paint, drawn)
                    if (!drawn) desktop.renderer.drawRoundedRect(r, item.radius * s, item.paint.firstColor().let { it.copy(alpha = it.alpha * item.alpha) }, item.width * s)
                }
                is DrawItem.Blur -> desktop.renderer.drawBlur(r, item.radius * s, Math.round(item.size * s).coerceAtLeast(1), item.passes, item.tone)
                is DrawItem.Image -> desktop.renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, opaque = false, alpha = item.alpha, clip = item.clip?.let(p), cornerRadius = item.radius * s)
                is DrawItem.Custom -> if (item.background && repaint != null && clip != null) drawBackground(item, items, index, repaint, clip, r, p) else item.paint(r)
                is DrawItem.Tint -> desktop.renderer.drawRoundedRect(r, item.radius * s, dev.caulis.render.Color(item.red, item.green, item.blue, item.alpha))
                is DrawItem.Effect -> {
                    val code = item.shader.code
                    val drawn = desktop.renderer.drawTextureEffect(item.texture, r.x, r.y, r.width, r.height, item.alpha, code, item.from, item.to, item.progress)
                    desktop.looks.shaderWorks(item.shader.name, code, item.shader.fileProblem ?: if (drawn) null else desktop.renderer.effectProblem(code) ?: "it does not compile")
                    if (!drawn) {
                        val f = p(item.fallback)
                        desktop.renderer.drawTexture(item.texture, f.x, f.y, f.width, f.height, opaque = false, alpha = item.alpha)
                    }
                }
            }
        }
    }

    /**
     * The buffer of a fullscreen window that is the only thing on screen (no menus, no
     * cursor drawn by us, nothing above it), if it is a GPU buffer exactly as big as the
     * screen. Such a picture can go to the display as it is.
     */
    private fun directCandidate(view: DesktopView): DmabufBuffer? {
        if (desktop.screenShader != null) return null // (every frame goes through it)
        val monitor = view.monitor
        // (A cursor on the cursor plane is no reason to draw: it is shown over the program's picture.)
        val all = itemsFor(view).filter { it.rect.overlaps(monitor.area) }
        val only = all.singleOrNull() as? DrawItem.Picture ?: return null
        val window = only.surface.roleObject as? Window ?: return null
        if (!window.fullscreen || only.rect != monitor.area) return null
        val buffer = only.surface.current.buffer?.dataAs<DmabufBuffer>() ?: return null
        // Turned as the monitor is (a program drawing for a screen on its side does so), and
        // exactly the screen's own pixels: shown as it is.
        if (only.surface.current.transform != monitor.transform.ordinal) return null
        val (width, height) = if (monitor.transform.sideways) monitor.pixelHeight to monitor.pixelWidth else monitor.pixelWidth to monitor.pixelHeight
        return buffer.takeIf { it.width == width && it.height == height }
    }

    // The visible programs' "tell me when it is shown" requests now belong to this frame.
    private fun takeFeedbacks(view: DesktopView) {
        for (surface in desktop.sceneBuilder.visible) {
            if (!desktop.sceneBuilder.shownMostlyOn(surface, view.monitor)) continue
            val feedbacks = surface.current.feedbackList
            if (feedbacks.isEmpty()) continue
            view.inFlight += feedbacks
            feedbacks.clear()
        }
    }

    // Marks everything that appeared, disappeared, moved or changed order since the view's last frame.
    internal fun compare(view: DesktopView, items: List<DrawItem>) {
        if (items === view.shown) return // nothing changed since the last comparison
        val before = view.shown.withIndex().associateBy({ it.value.key }, { it })
        val now = items.withIndex().associateBy({ it.value.key }, { it })
        // (What changed, at the finest level: to find what keeps a screen being drawn.)
        val why = if (desktop.renderLog.level >= 3) ArrayList<String>() else null
        for ((key, old) in before) {
            val new = now[key]
            if (new == null) {
                old.value.areas.forEach(view::damage)
                why?.add("gone $key")
            } else if (new.index != old.index || new.value.differsFrom(old.value)) {
                // (The background: only where it shows — not under the windows over it.)
                uncoveredAreas(old.value, view.shown, old.index).forEach(view::damage)
                uncoveredAreas(new.value, items, new.index).forEach(view::damage)
                why?.add("changed $key ${new.value.rect}")
            }
        }
        for ((key, new) in now) if (key !in before) {
            new.value.areas.forEach(view::damage)
            why?.add("new $key")
        }
        if (!why.isNullOrEmpty()) desktop.renderLog.trace { "${view.monitor.name} changed: ${why.take(6).joinToString("; ")}" }
        view.shown = items
    }

    // Surfaces kept updating although they may be hidden (e.g. shared in a screen recording).
    internal val keptUpdating = HashMap<Surface, Int>()

    fun keepUpdating(surface: Surface): AutoCloseable {
        keptUpdating[surface] = (keptUpdating[surface] ?: 0) + 1
        var open = true
        return AutoCloseable {
            if (!open) return@AutoCloseable
            open = false
            val count = (keptUpdating[surface] ?: 1) - 1
            if (count <= 0) keptUpdating.remove(surface) else keptUpdating[surface] = count
        }
    }

    internal fun isKeptUpdating(surface: Surface): Boolean {
        var current: Surface? = surface
        while (current != null) {
            if (current in keptUpdating) return true
            current = current.subsurface?.parent
        }
        return false
    }

    // The same after a frame of [view]: programs on screen hear of it from the monitor that
    // shows most of them (else a window on a 60 Hz monitor drew at the 144 Hz one's pace).
    internal fun frameShown(view: DesktopView?, timeMillis: Int) {
        for (surface in desktop.sceneBuilder.visible) if (surface.resource.isAlive && (view == null || desktop.sceneBuilder.shownMostlyOn(surface, view.monitor))) surface.sendFrameDone(timeMillis)
        for (surface in desktop.compositor.surfaces) {
            if (surface !in desktop.sceneBuilder.visible && isKeptUpdating(surface)) surface.sendFrameDone(timeMillis)
        }
        for (window in desktop.allWindows) {
            if (!window.awaitsSize) continue
            for (surface in window.surface.stack) if (surface !in desktop.sceneBuilder.visible && surface.resource.isAlive) surface.sendFrameDone(timeMillis)
        }
        scheduleHiddenRefresh()
    }

    private var hiddenRefresh: EventLoop.Timer? = null

    // Hidden programs waiting for their next frame get it once per HIDDEN_REFRESH_MILLIS.
    private fun scheduleHiddenRefresh() {
        if (hiddenRefresh != null || hiddenWaiting().isEmpty()) return
        hiddenRefresh = desktop.compositor.display.loop.after(HIDDEN_REFRESH_MILLIS) {
            hiddenRefresh = null
            val time = (System.nanoTime() / 1_000_000).toInt()
            for (surface in hiddenWaiting()) {
                surface.sendFrameDone(time)
                // Its pictures were never shown, and will not be until it is visible again.
                for (feedback in surface.current.presentationFeedbacks) feedback.discard()
                surface.current.feedbackList.clear()
            }
            scheduleHiddenRefresh()
        }
    }

    private fun hiddenWaiting(): List<Surface> = desktop.compositor.surfaces.filter {
        it.current.frameCallbacks.isNotEmpty() && it !in desktop.sceneBuilder.visible && !isKeptUpdating(it)
    }
}
