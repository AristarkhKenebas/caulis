package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.tiling.Floating
import dev.caulis.tiling.TilingCommand

/**
 * Where windows go: layouts' places applied together once programs have drawn, margins, floating
 * places and cascades, what rules ask for when a window opens.
 */
internal class DesktopArrange(private val desktop: Desktop) {

    // A layout's places for [windows] (fewer: the rest are not placed, as Floating does); one
    // that fails leaves every window the whole [area] meanwhile.
    internal fun arrangeSafely(workspace: Workspace, windows: List<Window>, area: Rect, focused: Window?): List<Rect> =
        userCode("The layout ${workspace.layout.name}", null) { workspace.model.arrange(windows, area, focused) } ?: windows.map { area }

    // Floats or not: your floatRule, or (if it fails) the usual answer.
    internal fun floats(window: Window): Boolean =
        userCode("Your floatRule", window.parent != null || window.fixedSize || window.prefersFloating) { desktop.floatRule(window) }

    private var cascade = 0

    // The free floating place of each window (also kept while it is tiled or maximized).
    internal val floatingRects = HashMap<Window, Rect>()

    // New sizes asked for, positions applied together once every window has drawn in its
    // new size (or after a short wait): so neighbours never jump one after another.
    internal val pending = HashMap<Window, Rect>()

    private var pendingTimer: EventLoop.Timer? = null

    // Puts the tiled windows of [workspace] where its layout wants them. With [extra], also
    // a window about to appear (to learn its size before its first frame) — without moving anyone.
    internal fun arrange(workspace: Workspace, extra: Window? = null): Map<Window, Rect> {
        val tiled = workspace.tiledWindows() + listOfNotNull(extra)
        if (tiled.isEmpty()) return emptyMap()
        val top = workspace.windows.lastOrNull { it in tiled } ?: extra
        val area = (workspace.monitor?.takeIf { it in desktop.monitorList } ?: desktop.focusedMonitor).usableArea
        val rects = arrangeSafely(workspace, tiled, area, top)
        val targets = tiled.zip(rects).associate { (window, rect) -> window to fit(window, withMargins(window, rect, area)) }
        desktop.layoutLog.trace { "Arrange workspace ${workspace.number} (${workspace.layout.name}) in $area: " + targets.entries.joinToString { (w, r) -> "${w.title.take(24)}=$r" } }
        if (extra == null) place(targets)
        return targets
    }

    fun tilingCommand(command: TilingCommand): Boolean {
        val focused = desktop.focusedWindow?.takeIf { it.tiled }
        val done = desktop.activeWorkspace.model.command(command, focused)
        desktop.layoutLog.trace { "Tiling command $command for $focused: ${if (done) "done" else "not possible here"}" }
        // (Asked even when nothing moved: "focus the master" changes no place.)
        val wanted = desktop.activeWorkspace.model.takeFocus() as? Window
        if (wanted != null && wanted in desktop.activeWorkspace.windows) desktop.focus(wanted)
        if (!done) return wanted != null
        arrange(desktop.activeWorkspace)
        desktop.changed()
        return true
    }

    fun marginsChanged(window: Window) {
        desktop.workspaceOf(window)?.let { arrange(it); desktop.changed() }
    }

    // [place] with [window]'s margins: smaller, or (negative) bigger — but inside [area] and at least 1 x 1.
    private fun withMargins(window: Window, place: Rect, area: Rect): Rect {
        val m = try {
            desktop.marginsFor(window)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Your margins rule failed for $window (ignored): $e", e)
            null
        } ?: return place
        val left = maxOf(place.x + m.left, area.x)
        val top = maxOf(place.y + m.top, area.y)
        val right = minOf(place.right - m.right, area.right)
        val bottom = minOf(place.bottom - m.bottom, area.bottom)
        return Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    // Respects the program's own size limits: a window that cannot be as big (or small) as
    // its tile is centred in it instead of being pushed off screen (R5).
    internal fun fit(window: Window, place: Rect): Rect {
        val tile = desktop.withoutTitle(window, place) // the compositor's title bar takes the top of the tile
        val w = limitSize(tile.width, window.minWidth, window.maxWidth)
        val h = limitSize(tile.height, window.minHeight, window.maxHeight)
        return Rect(tile.x + (tile.width - w) / 2, tile.y + (tile.height - h) / 2, w, h)
    }

    private fun place(targets: Map<Window, Rect>) {
        for ((window, rect) in targets) {
            val visibleRect = window.visibleRect
            val becameTiled = !window.tiled
            window.tiled = true
            if (becameTiled || visibleRect.width != rect.width || visibleRect.height != rect.height) {
                window.configure(rect.width, rect.height)
            }
            pending[window] = rect
        }
        applyWhenReady()
    }

    // Applies all pending positions if every window has its new size by now; else waits a bit.
    internal fun applyWhenReady() {
        if (pending.isEmpty()) return
        val ready = pending.all { (window, rect) ->
            val visibleRect = window.visibleRect
            visibleRect.width == rect.width && visibleRect.height == rect.height
        }
        if (ready) {
            applyPending()
        } else if (pendingTimer == null) {
            pendingTimer = desktop.compositor.display.loop.after(TRANSACTION_TIMEOUT_MILLIS) { applyPending() }
        }
    }

    private fun applyPending() {
        pendingTimer?.cancel()
        pendingTimer = null
        for ((window, rect) in pending) desktop.animator.moveSmoothly(window, rect.x, rect.y)
        pending.clear()
        desktop.changed()
    }

    // Takes a window out of tiling: it gets its floating place and size back.
    internal fun untile(window: Window) {
        pending.remove(window)
        if (!window.tiled) return
        window.tiled = false
        val floating = floatingRects[window] ?: cascadeRect(window.visibleRect.width, window.visibleRect.height)
        desktop.animator.moveSmoothly(window, floating.x, floating.y)
        window.configure(floating.width, floating.height)
        desktop.changed()
    }

    // The next free spot of the cascade (top left, stepping down and right).
    internal fun cascadeRect(windowWidth: Int, windowHeight: Int, area: Rect = desktop.focusedMonitor.area): Rect {
        val step = 32
        val x = (40 + cascade * step).coerceAtMost((area.width - windowWidth).coerceAtLeast(0))
        val y = (40 + cascade * step).coerceAtMost((area.height - windowHeight).coerceAtLeast(0))
        cascade = (cascade + 1) % 8
        return Rect(area.x + x, area.y + y, windowWidth, windowHeight)
    }

    fun setFloating(window: Window, floating: Boolean) {
        desktop.layoutLog.info { "$window: ${if (floating) "floating" else "tiled"}" }
        val workspace = desktop.workspaceOf(window) ?: return
        if (floating) {
            if (!window.tiled && window in workspace.floating) return
            floatingRects.getOrPut(window) { cascadeRect(window.visibleRect.width, window.visibleRect.height) }
            workspace.floating += window
            untile(window)
        } else {
            pinned -= window // (tiled: in one workspace's layout)
            floatingRects[window] = desktop.currentRect(window)
            workspace.floating -= window
        }
        arrange(workspace)
        desktop.changed()
    }

    internal val pinned = HashSet<Window>()

    // The window of [workspace] that gets the keyboard when it is shown: the top one of its
    // own — a pinned window (which only came along) just when there is no other.
    internal fun topWindow(workspace: Workspace): Window? =
        workspace.windows.lastOrNull { it !in pinned } ?: workspace.windows.lastOrNull()

    fun setPinned(window: Window, on: Boolean) {
        if (on == desktop.isPinned(window) || (on && desktop.workspaceOf(window) == null)) return
        desktop.layoutLog.info { "$window: ${if (on) "pinned (on every workspace)" else "no longer pinned"}" }
        if (on) {
            if (window.tiled) setFloating(window, true)
            pinned += window
        } else {
            pinned -= window
        }
        desktop.changed()
    }

    // What placementFor said, from a window's first configure until it appears.
    internal val placements = HashMap<Window, WindowPlacement>()

    internal fun placementOf(window: Window): WindowPlacement = placements.getOrPut(window) {
        try {
            desktop.placementFor(window) ?: WindowPlacement.USUAL
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Your placement rule failed for $window (ignored): $e", e)
            WindowPlacement.USUAL
        }
    }

    internal fun monitorNamed(name: String?): Monitor? = name?.let { wanted -> desktop.monitorList.firstOrNull { it.name == wanted } }

    // The placement's workspace if there is one with that number (else it is not used: a window
    // on workspace 42 of 9 could never be reached).
    private fun validWorkspace(placement: WindowPlacement): Int? = placement.workspace?.let { number ->
        if (number <= desktop.workspaceCount) number else null.also { desktop.layoutLog.warn("There is no workspace $number (workspaceCount is $desktop.workspaceCount): the window opens where it would anyway") }
    }

    // The workspace a new window opens on: the one its placement says, or the one with the focus.
    internal fun workspaceFor(placement: WindowPlacement): Workspace {
        val monitor = monitorNamed(placement.monitor) ?: desktop.focusedMonitor
        return validWorkspace(placement)?.let { desktop.workspace(it, monitor) } ?: monitor.workspace
    }

    // Its floating place on [monitor]: as the placement says, else the next step of the cascade.
    // [width] x [height] is its picture; its title bar (if ours) stays on screen above it.
    internal fun floatingPlace(window: Window, placement: WindowPlacement, monitor: Monitor, width: Int, height: Int): Rect {
        val w = placement.width ?: width
        val h = placement.height ?: height
        val bar = desktop.titleHeight(window)
        val area = monitor.usableArea
        return when {
            placement.x != null || placement.y != null -> Rect(area.x + (placement.x ?: 0), area.y + (placement.y ?: 0) + bar, w, h)
            placement.center -> Rect(area.x + (area.width - w) / 2, area.y + (area.height - h - bar) / 2 + bar, w, h)
            else -> cascadeRect(w, h, monitor.area).translate(0, bar)
        }
    }

    fun applyPlacement(window: Window, placement: WindowPlacement) {
        if (desktop.scratchpadOf(window) != null || desktop.workspaceOf(window) == null) return
        desktop.layoutLog.info { "$window: placed by a rule ($placement)" }
        placement.specialWorkspace?.let { name -> return desktop.moveToSpecialWorkspace(window, name, silent = !placement.goToWorkspace) }
        val monitor = monitorNamed(placement.monitor)
        when {
            validWorkspace(placement) != null -> desktop.moveToWorkspace(window, desktop.workspace(placement.workspace!!, monitor ?: desktop.monitorOf(window)))
            monitor != null -> desktop.moveToWorkspace(window, monitor.workspace)
        }
        placement.floating?.let { setFloating(window, it) }
        if (placement.width != null || placement.height != null || placement.x != null || placement.y != null || placement.center) {
            val visible = window.visibleRect
            val place = floatingPlace(window, placement, desktop.monitorOf(window), visible.width, visible.height).let { place ->
                desktop.behaviorOf(window).limit(place.width, place.height).let { (w, h) -> Rect(place.x, place.y, w, h) }
            }
            floatingRects[window] = place
            if (!window.tiled && !window.maximized && !window.fullscreen) {
                desktop.animator.moveSmoothly(window, place.x, place.y)
                window.configure(place.width, place.height)
            }
        }
        placement.maximized?.let { desktop.setMaximized(window, it) }
        placement.fullscreen?.let { desktop.setFullscreen(window, it) }
        placement.pinned?.let { setPinned(window, it) }
        if (placement.goToWorkspace) desktop.focus(window)
        desktop.changed()
    }
}
