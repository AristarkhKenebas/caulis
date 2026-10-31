package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.Color
import kotlin.math.roundToInt

/**
 * What there is to draw now, bottom to top: the list every frame compares with what a screen shows.
 */
internal class DesktopScene(private val desktop: Desktop) {

    // Every surface shown learns the monitors it is on and draws at the largest of their scales.
    private fun placeSurfaces(items: List<DrawItem>) {
        if (desktop.monitorList.size == 1 && desktop.placeholder != null) return // no real monitor yet: the default holds
        for (item in items) {
            if (item !is DrawItem.Picture) continue
            val on = desktop.monitorList.filter { it.area.intersect(item.rect) != null }
            if (on.isEmpty()) continue
            item.surface.placeOn(on.mapNotNull { it.output }, on.maxOf { it.scale })
        }
    }

    // Windows bottom to top as drawn, on every monitor: tiled ones, floating ones above
    // them, fullscreen on top.
    internal fun stackingOrder(): List<Window> = shownWorkspaces().flatMap { workspace ->
        val (fullscreen, others) = workspace.windows.partition { it.fullscreen }
        others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

    // The workspaces on screen, one per monitor.
    private fun shownWorkspaces(): List<Workspace> = desktop.monitorList.map { it.workspace }.distinct()

    // Surfaces visible right now; they get "frame done" at every refresh.
    internal val visible = LinkedHashSet<Surface>()

    // The monitor each visible picture is shown on most (only with more than one monitor).
    private val surfaceHomes = HashMap<Surface, Monitor>()

    // True if [monitor] is the one [surface] is shown on most (or nobody knows: then any is).
    internal fun shownMostlyOn(surface: Surface, monitor: Monitor): Boolean {
        val home = surfaceHomes[surface] ?: return true
        return home === monitor || home !in desktop.monitorList
    }

    // The scene (everything except the cursor), rebuilt only after a change.
    private var scene: List<DrawItem> = emptyList()

    private val sceneVisible = ArrayList<Surface>()

    private var sceneVersion = -1L

    // The scene plus the cursor, rebuilt when the scene changed or the pointer moved.
    internal var items: List<DrawItem> = emptyList()

    private var itemsVersion = -1L

    private var itemsPointerX = Double.NaN

    private var itemsPointerY = Double.NaN

    private var itemsCursorVersion = -1L

    internal var itemsCursorStart = 0

    /** Everything to draw now, bottom to top, the cursor last. */
    internal fun items(): List<DrawItem> {
        if (itemsVersion == desktop.version && itemsPointerX == desktop.pointerX && itemsPointerY == desktop.pointerY && itemsCursorVersion == desktop.cursors.cursorVersion) return items
        val all = ArrayList<DrawItem>(scene().size + 2)
        all += scene
        visible.clear()
        visible += sceneVisible
        // The frame before a scale change, fading out over the new one (the cursor stays above).
        for ((monitor, fade) in desktop.scaleFade.fades) all += DrawItem.Image("$FADE_KEY ${monitor.name}", fade.texture, monitor.area, alpha = desktop.scaleFade.fadeAlphas[monitor] ?: 1f)
        val cursorStart = all.size
        itemsCursorStart = cursorStart
        desktop.cursors.addCursor(all)
        for (i in cursorStart until all.size) (all[i] as? DrawItem.Picture)?.let { visible += it.surface }
        surfaceHomes.clear()
        if (desktop.monitorList.size > 1) {
            // Where each picture is shown most (frame callbacks and presentation times come from there).
            val shownArea = HashMap<Surface, HashMap<Monitor, Long>>()
            for (item in all) {
                if (item !is DrawItem.Picture) continue
                for (monitor in desktop.monitorList) {
                    if (item.only != null && item.only !== monitor) continue
                    val part = item.rect.intersect(monitor.area) ?: continue
                    shownArea.getOrPut(item.surface) { HashMap() }.merge(monitor, part.width.toLong() * part.height, Long::plus)
                }
            }
            for ((surface, areas) in shownArea) surfaceHomes[surface] = areas.maxBy { it.value }.key
        }
        items = all
        itemsVersion = desktop.version
        itemsPointerX = desktop.pointerX
        itemsPointerY = desktop.pointerY
        itemsCursorVersion = desktop.cursors.cursorVersion
        return all
    }

    /**
     * Windows and popups to draw, bottom to top. Things nobody can see are left out:
     * outside the desktop, or completely behind something opaque.
     */
    private fun scene(): List<DrawItem> {
        if (sceneVersion == desktop.version) return scene
        desktop.looks.paintsMoving = false
        desktop.looks.focusChanging = false
        val all = ArrayList<DrawItem>()
        if (desktop.lockHost.lockHidesDesktop) {
            // Nothing of the desktop: a plain colour and the locker's pictures, on every monitor
            // (and our widgets usable on the lock screen, above them).
            val color = if (!desktop.lockHost.sessionLocked || desktop.lockHost.lock?.alive == true) desktop.lockColor else desktop.abandonedLockColor
            for (monitor in desktop.monitorList) {
                all += DrawItem.Color("$LOCK_BACKGROUND ${monitor.name}", monitor.area, color.red, color.green, color.blue, covers = listOf(monitor.area))
            }
            desktop.lockBackdrop?.takeIf { !desktop.lockHost.sessionLocked }?.let { addLockBackdrop(all, it) }
            for (lockSurface in desktop.lockHost.lockSurfaces()) desktop.screens.lockMonitor(lockSurface).area.let { addTree(all, lockSurface.surface, it.x, it.y) }
            for (level in DrawingLevel.entries) desktop.widgetHost.addWidgets(all, level, onLockScreen = true)
            sceneVisible.clear()
            for (item in all) if (item is DrawItem.Picture) sceneVisible += item.surface
            scene = all
            sceneVersion = desktop.version
            return all
        }
        val now = System.nanoTime()
        val camera = desktop.overview.cameraAt(now)
        val placed = placeDirected(now)
        val (fullscreen, normal) = stackingOrder().filter { !desktop.directed(it) }.partition { it.fullscreen }
        // Our background on each monitor, below a wallpaper program's (which wins).
        val allMonitors = desktop.screens.desktopBounds()
        desktop.wallpaperShaders.sample(desktop.pointerX, desktop.pointerY, now)
        for (monitor in desktop.monitorList) all += desktop.backdrops.items(monitor, monitor.workspace.number, desktop.pointerX, desktop.pointerY, now, allMonitors, desktop.largestScale)
        desktop.widgetHost.addDrawings(all, DrawingLevel.WALLPAPER)
        addLayers(all, LayerShell.Layer.BACKGROUND)
        addLayers(all, LayerShell.Layer.BOTTOM)
        desktop.widgetHost.addDrawings(all, DrawingLevel.BELOW_WINDOWS)
        // A monitor changing its workspace: how the old and the new one are drawn now. The new
        // one's windows (and the old one's) show only on that monitor while they move.
        class Change(val monitor: Monitor, val frame: WorkspaceFrame)
        val changes = HashMap<Workspace, Change>()
        for ((monitor, slide) in desktop.animator.slides.toList()) {
            val picked = desktop.animator.pick("workspaces", desktop.animations.workspaceSwitch)
            val animation = picked.look
            val t = desktop.animator.animationProgress(slide.start, picked.millis, now)
            if (t == null || desktop.inOverview || monitor !in desktop.monitorList || monitor.workspace !== slide.to) {
                desktop.animator.slides -= monitor
                continue
            }
            val frame = try {
                animation.frame(picked.curve.safeAt(t), slide.direction, monitor.area)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                desktop.log.error("Your workspace animation failed (workspaces change at once): $e", e)
                desktop.animator.slides -= monitor
                continue
            }
            changes[slide.to] = Change(monitor, frame)
            // The workspace before, on its way out (unless another monitor shows it now).
            if (desktop.monitorList.none { it.workspace === slide.from }) {
                val start = all.size
                val outgoing = DesktopOverview.Camera(1.0, frame.outgoingDx, frame.outgoingDy)
                for (window in stacking(slide.from)) addWindow(all, window, now, camera = outgoing)
                fade(all, start, frame.outgoingAlpha, monitor)
            }
        }
        fun changeOf(window: Window) = changes.entries.firstOrNull { (workspace, _) -> window in workspace.windows || window in workspace.minimized }?.value
        // The overview's camera is on the monitor with the focus only; a changing workspace moves.
        fun cameraFor(window: Window): DesktopOverview.Camera {
            if (camera !== DesktopOverview.Camera.NONE) return if (window in desktop.activeWorkspace.windows || window in desktop.activeWorkspace.minimized) camera else DesktopOverview.Camera.NONE
            val change = changeOf(window) ?: return DesktopOverview.Camera.NONE
            return DesktopOverview.Camera(1.0, change.frame.incomingDx, change.frame.incomingDy)
        }
        fun addWindowNow(window: Window) {
            val change = changeOf(window)
            val start = all.size
            addWindow(all, window, now, camera = cameraFor(window))
            if (change != null) fade(all, start, change.frame.incomingAlpha, change.monitor)
        }
        for (window in normal) addWindowNow(window)
        // The director's windows, where it put them, lowest first.
        for ((window, place) in placed) addDirected(all, window, place)
        // Windows going away: their last look, as the close animation says.
        desktop.animator.addFlights(all, now, DesktopAnimations.FlightKind.CLOSE)
        desktop.widgetHost.addDrawings(all, DrawingLevel.ABOVE_WINDOWS)
        addLayers(all, LayerShell.Layer.TOP)
        // Windows on their way into the dock and out of it (above it, so they are seen going into their icon).
        desktop.animator.addFlights(all, now, DesktopAnimations.FlightKind.MINIMIZE)
        desktop.animator.addFlights(all, now, DesktopAnimations.FlightKind.RESTORE)
        // Fullscreen windows cover panels and bars (not notifications on the overlay layer).
        for (window in fullscreen) addWindowNow(window)
        // Shown scratchpads above every workspace, the last shown on top.
        for (pad in desktop.pads.shownPads) {
            val overlay = pad.style as? Scratchpad.Overlay
            val amount = if (pad.isSpecial) desktop.pads.padAmount(pad, now) else 1f
            if (pad.isSpecial) {
                // A special workspace: what is behind it blurred and dimmed, as its settings say (the dim fading with it).
                val settings = desktop.specialWorkspaces
                if (!pad.leaving) settings.blur?.let { blur -> all += DrawItem.Blur(EffectKey("blur special", pad), desktop.pads.padArea(pad), 0f, blur.size, blur.passes, blur.tone) }
                if (settings.dim > 0f && amount > 0f) all += DrawItem.Color("dim special ${pad.name}", desktop.pads.padArea(pad), 0f, 0f, 0f, alpha = settings.dim * amount)
            } else if (overlay != null && !pad.leaving) {
                all += DrawItem.Color("dim ${pad.name}", desktop.pads.padArea(pad), 0f, 0f, 0f, alpha = overlay.dim)
            }
            val fade = if (pad.isSpecial && desktop.specialWorkspaces.look.fade) amount else 1f
            val first = all.size
            for (window in pad.windows) addWindow(all, window, now, leaving = pad.leaving, fade = fade)
            // A special workspace's windows lie side by side: one's shadow or glow goes under the others too.
            if (pad.isSpecial) shadowsFirst(all, first)
        }
        addLayers(all, LayerShell.Layer.OVERLAY)
        for (popup in desktop.popupHost.popups) {
            if (camera !== DesktopOverview.Camera.NONE) break // menus are not part of the overview
            if (!desktop.popupHost.popupShown(popup) || !desktop.lockHost.drawnBehindLock(popup.surface)) continue
            val (x, y) = desktop.hits.popupOrigin(popup)
            addTree(all, popup.surface, x, y)
        }
        if (camera === DesktopOverview.Camera.NONE) for ((surface, place) in desktop.overlayHost.unmanaged) if (desktop.lockHost.drawnBehindLock(surface)) addTree(all, surface, place.first, place.second)
        desktop.widgetHost.addDrawings(all, DrawingLevel.ON_TOP)
        // Our own notices above everything (not on a locked screen: what they say is private).
        if (!desktop.locked) for (monitor in desktop.monitorList) all += desktop.noticeBoard.items(monitor, desktop.monitorList, desktop.noticeFallback(monitor))
        if (!desktop.locked) desktop.overlay?.takeIf { it !== desktop.overlayHost.brokenOverlay }?.let { open ->
            for (monitor in desktop.monitorList) {
                val canvas = OverlayCanvas(monitor, "overlay ${monitor.name}")
                try {
                    open.draw(monitor, canvas)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    desktop.overlayLog.error("$open failed to draw; closing it", e)
                    desktop.overlayHost.brokenOverlay = open
                    desktop.onChange()
                    break
                }
                if (desktop.overlayHost.brokenOverlay !== open) all += canvas.items
            }
        }
        if (!desktop.locked) all += desktop.osd.items(desktop.focusedMonitor, System.nanoTime())

        // Leave out what nobody can see; only the rest counts as visible.
        val kept = cullHidden(all, desktop.screens.desktopBounds())
        // A moving wallpaper shader goes on only where it is seen (not under a wallpaper program, a fullscreen window).
        desktop.backdrops.sceneBuilt()
        desktop.wallpaperShaders.sceneBuilt(kept)
        sceneVisible.clear()
        for (item in kept) if (item is DrawItem.Picture) sceneVisible += item.surface
        placeSurfaces(kept)
        scene = kept
        sceneVersion = desktop.version
        return kept
    }

    // The items from [start] on show only on [monitor], at [alpha] (a sliding or fading workspace).
    private fun fade(all: MutableList<DrawItem>, start: Int, alpha: Float, monitor: Monitor) {
        for (i in start until all.size) {
            val item = all[i]
            item.only = monitor
            if (alpha < 1f) all[i] = item.fadedBy(alpha)
        }
    }

    // A workspace's windows bottom to top, as drawn.
    private fun stacking(workspace: Workspace): List<Window> {
        val (fullscreen, others) = workspace.windows.partition { it.fullscreen }
        return others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

    // The wallpaper behind the lock screen: nothing else of the desktop (see [lockBackdrop]).
    private fun addLockBackdrop(all: MutableList<DrawItem>, backdrop: LockBackdrop) {
        val now = System.nanoTime()
        if (backdrop.wallpaper) {
            desktop.wallpaperShaders.sample(desktop.pointerX, desktop.pointerY, now)
            val allMonitors = desktop.screens.desktopBounds()
            for (monitor in desktop.monitorList) all += desktop.backdrops.items(monitor, monitor.workspace.number, desktop.pointerX, desktop.pointerY, now, allMonitors, desktop.largestScale)
            desktop.widgetHost.addDrawings(all, DrawingLevel.WALLPAPER, withWidgets = false)
        }
        for (monitor in desktop.monitorList) {
            backdrop.blur?.let { b -> all += DrawItem.Blur(EffectKey("blur lock", monitor), monitor.area, 0f, b.size, b.passes, b.tone) }
            if (backdrop.dim > 0f) all += DrawItem.Color("dim lock ${monitor.name}", monitor.area, 0f, 0f, 0f, alpha = backdrop.dim)
        }
    }

    private fun addLayers(all: MutableList<DrawItem>, layer: LayerShell.Layer) {
        for (layerSurface in desktop.layerHost.layerSurfaces) {
            if (!layerSurface.mapped || layerSurface.current.layer != layer) continue
            val surface = layerSurface.surface
            if (!desktop.lockHost.drawnBehindLock(surface)) continue
            val rect = Rect(layerSurface.x, layerSurface.y, surface.width, surface.height)
            // Everything behind it darker (a launcher in front of the desktop).
            val dim = desktop.layerHost.layerBehaviorOf(layerSurface).dimAround
            if (dim > 0f) all += DrawItem.Tint(EffectKey("dim around", layerSurface), desktop.monitorOf(layerSurface).area, 0f, 0f, 0f, 0f, dim)
            val look = desktop.looks.addEffects(all, layerSurface, surface, Rect(0, 0, surface.width, surface.height), rect, 1.0, userCode("Your layerEffects", Effects.NONE) { desktop.layerEffects(layerSurface) }, focused = true)
            addTree(all, surface, layerSurface.x, layerSurface.y, look = look)
        }
    }

    // A window where it is (or on its way, see glides); a leaving one only while it moves.
    internal fun addWindow(all: MutableList<DrawItem>, window: Window, now: Long, leaving: Boolean = false, camera: DesktopOverview.Camera = DesktopOverview.Camera.NONE, plain: Boolean = false, fade: Float = 1f) {
        // Coming back out of the dock: its picture flies (see flights) until it is there.
        if (!plain && desktop.animator.flights.any { it.window === window && it.kind == DesktopAnimations.FlightKind.RESTORE }) {
            desktop.animator.shownRects[window] = desktop.windowRoles.currentRect(window)
            return
        }
        val visibleRect = window.visibleRect
        val target = desktop.windowRoles.currentRect(window)
        val glide = if (plain) null else desktop.animator.glides[window]
        val shown = glide?.let { desktop.animator.glidePosition(it, target, now) }
        if (glide != null && shown == null) desktop.animator.glides -= window
        if (leaving && shown == null) return // gone out of sight
        var world = shown ?: target
        // Tiled windows follow the fingers while the view is dragged.
        if ((desktop.overview.dragX != 0.0 || desktop.overview.dragY != 0.0) && window.tiled && window in desktop.activeWorkspace.windows) {
            world = world.translate(desktop.overview.dragX.roundToInt(), desktop.overview.dragY.roundToInt())
        }
        desktop.animator.shownRects[window] = world
        // Appearing: as the open animation says (only its picture: the window itself is in place).
        var alpha = fade
        val appearSince = if (plain) null else desktop.animator.appearing[window]
        if (appearSince != null) {
            val picked = desktop.animator.pick("windowsIn", desktop.animations.windowOpen)
            val animation = picked.look
            val t = desktop.animator.animationProgress(appearSince, picked.millis, now)
            val frame = if (t == null) null else try {
                animation.frame(Motion(window, world, world, picked.curve.safeAt(t), t, desktop.monitorOf(window).usableArea))
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                desktop.log.error("Your window animation failed (it is drawn without): $e", e)
                null
            }
            if (frame == null) {
                desktop.animator.appearing -= window
                desktop.animator.appearingPictures.remove(window)?.close()
            } else if (frame.shader != null) {
                // Through a shader: the whole window as one picture, made anew each frame.
                val picture = desktop.animator.pictureOf(window)
                (if (picture != null) desktop.animator.appearingPictures.put(window, picture.first) else desktop.animator.appearingPictures.remove(window))?.close()
                if (picture != null) {
                    // The picture has the window's shadow around it: every rectangle grows the same.
                    val (_, texture, box) = picture
                    fun around(r: Rect) = Rect(r.x + box.x - world.x, r.y + box.y - world.y, r.width + box.width - world.width, r.height + box.height - world.height)
                    all += desktop.animator.frameItem(EffectKey("appearing", window), texture, WindowFrame(around(frame.rect), frame.alpha, frame.shader, around(frame.shaderFrom), around(frame.shaderTo), frame.progress))
                    return
                }
            } else {
                world = frame.rect
                alpha = frame.alpha * fade
            }
        }
        val rect = if (camera === DesktopOverview.Camera.NONE) world else camera.apply(world)
        desktop.animator.screenRects[window] = rect
        // While gliding (or zoomed out), the picture is stretched to the in-between size.
        val scaleX = if (target.width > 0) rect.width.toDouble() / target.width else 1.0
        val scaleY = if (target.height > 0) rect.height.toDouble() / target.height else 1.0
        val fx = desktop.looks.faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", desktop.effects) { desktop.windowEffects(window) }, alpha)
        // The compositor's title bar over it, if it has one: effects go around both.
        val bar = desktop.titleHeight(window).let { if (it > 0) Rect(rect.x, rect.y - (it * scaleY).roundToInt(), rect.width, (it * scaleY).roundToInt()) else null }
        val frame = bar?.union(rect) ?: rect
        // (A window of a scratchpad going keeps the look of the focus it had: border and glow fade with it.)
        val looksFocused = window === desktop.focusedWindow || (leaving && desktop.scratchpadOf(window)?.focusedAsItWent?.get() === window)
        val look = desktop.looks.addEffects(all, window, window.surface, visibleRect, frame, minOf(scaleX, scaleY), fx, focused = looksFocused)
        if (bar != null) all += DrawItem.Image(EffectKey("title", window), desktop.ownTitleBars.texture(window, bar), bar, look.clip, look.radius, alpha = alpha)
        addTree(all, window.surface, rect.x - (visibleRect.x * scaleX).roundToInt(), rect.y - (visibleRect.y * scaleY).roundToInt(), scaleX, scaleY, look)
        if (look.dim > 0f) all += DrawItem.Tint(EffectKey("dim", window), frame, look.radius, 0f, 0f, 0f, look.dim * alpha)
    }

    // Asks the director where each of its windows is now; kept for the pointer (lowest first).
    private fun placeDirected(now: Long): List<Pair<Window, WindowPlace>> {
        val d = desktop.director
        if (d == null) {
            desktop.places = emptyMap()
            return emptyList()
        }
        val placed = ArrayList<Pair<Window, WindowPlace>>()
        for (window in desktop.allWindows) {
            if (!window.mapped || !desktop.directed(window)) continue
            val place = try {
                if (!d.onScreen(window)) continue
                d.place(window, now)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                desktop.log.error("Your WindowDirector failed to place $window (it stays where it was)", e)
                val visible = window.visibleRect
                WindowPlace(window.x.toDouble(), window.y.toDouble(), visible.width.toDouble(), visible.height.toDouble())
            } ?: continue
            placed += window to place
        }
        placed.sortBy { it.second.z } // (stable: equal ones keep the desktop's order)
        desktop.places = placed.toMap(LinkedHashMap())
        return placed
    }

    // A director's window at [place]: its picture stretched into it, with its effects.
    private fun addDirected(all: MutableList<DrawItem>, window: Window, place: WindowPlace) {
        if (place.alpha <= 0f || place.width <= 0 || place.height <= 0) return
        val rect = place.rect
        // Its menus open from there, and it counts as on the monitor it is drawn on.
        window.x = rect.x
        window.y = rect.y
        desktop.animator.shownRects[window] = rect
        desktop.animator.screenRects[window] = rect
        val visible = window.visibleRect
        val scaleX = if (visible.width > 0) place.width / visible.width else 1.0
        val scaleY = if (visible.height > 0) place.height / visible.height else 1.0
        val scale = minOf(scaleX, scaleY)
        var fx = desktop.looks.faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", desktop.effects) { desktop.windowEffects(window) }, place.alpha)
        place.cornerRadius?.let { r -> fx = fx.copy(cornerRadius = if (scale > 0) Math.round(r / scale).toInt() else 0) }
        val look = desktop.looks.addEffects(all, window, window.surface, visible, rect, scale, fx, focused = window === desktop.focusedWindow)
        val clip = when {
            place.clip == null -> look.clip
            look.clip == null -> place.clip
            else -> look.clip.intersect(place.clip) ?: return
        }
        val clipped = DesktopLooks.Look(clip, look.radius, look.alpha, look.dim)
        desktop.widgetHost.addDecorations(all, window, place, above = false)
        addTree(all, window.surface, rect.x - (visible.x * scaleX).roundToInt(), rect.y - (visible.y * scaleY).roundToInt(), scaleX, scaleY, clipped)
        if (look.dim > 0f) all += DrawItem.Tint(EffectKey("dim", window), rect, look.radius, 0f, 0f, 0f, look.dim * place.alpha)
        desktop.widgetHost.addDecorations(all, window, place, above = true)
    }

    internal fun addTree(
        items: MutableList<DrawItem>, surface: Surface, x: Int, y: Int,
        scaleX: Double = 1.0, scaleY: Double = 1.0, look: DesktopLooks.Look = DesktopLooks.Look.PLAIN,
    ) {
        val scaled = scaleX != 1.0 || scaleY != 1.0
        for (child in surface.stack) {
            if (child === surface) {
                val texture = surface.texture ?: continue
                val rect = Rect(x, y, (surface.width * scaleX).roundToInt(), (surface.height * scaleY).roundToInt())
                // A stretched or faded picture hides nothing for sure (its edges move every frame).
                val covers = if (scaled || look.alpha < 1f) emptyList() else coversOf(surface.opaqueRects().map { it.translate(x, y) }, look)
                items += DrawItem.Picture(surface, texture, rect, surface.opaque, covers, look.alpha, look.clip, look.radius, surface.textureSource, surface.current.transform)
            } else {
                val sub = child.subsurface ?: continue
                addTree(items, child, x + (sub.x * scaleX).roundToInt(), y + (sub.y * scaleY).roundToInt(), scaleX, scaleY, look)
            }
        }
    }

    // What an opaque picture still hides when clipped with round corners: everything but the corners.
    private fun coversOf(opaque: List<Rect>, look: DesktopLooks.Look): List<Rect> {
        val clip = look.clip ?: return opaque
        val r = Math.ceil(look.radius.toDouble()).toInt()
        val wide = Rect(clip.x, clip.y + r, clip.width, clip.height - 2 * r)
        val tall = Rect(clip.x + r, clip.y, clip.width - 2 * r, clip.height)
        return opaque.flatMap { listOfNotNull(it.intersect(wide), it.intersect(tall)) }
    }
}
