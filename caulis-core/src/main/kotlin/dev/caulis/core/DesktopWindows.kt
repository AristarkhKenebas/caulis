package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.tiling.Floating

/**
 * Windows' lives: opening and closing, what programs ask for (maximize, fullscreen, minimize,
 * moving, resizing), and the states the desktop gives them.
 */
internal class DesktopWindows(private val desktop: Desktop) {

    fun windowInfoChanged(window: Window) {
        desktop.windowsLog.trace { "Now called $window" }
        // (Rules and title bars may show it. changed() tells those following the window list too.)
        desktop.changed()
        if (window.mapped) notifyEach(desktop.events.windowInfoListeners, window, "window-info-changed")
    }

    // The layout decides the size before the first frame, so a new window never shows up
    // in a wrong size first (R1). Maximized/fullscreen apps get the whole desktop.
    fun initialSize(window: Window): Pair<Int, Int> {
        if (desktop.directed(window)) return userCode("Your WindowDirector (initialSize)", 0 to 0) { desktop.director!!.initialSize(window) }
        desktop.pads.scratchpadFor(window)?.let { pad ->
            window.tiled = pad.style is Scratchpad.Overlay // told in the very first configure
            return desktop.pads.padTargets(pad, extra = window)[window]?.let { it.width to it.height } ?: (0 to 0)
        }
        // Where it goes (rules), told in the very first configure too.
        val placement = desktop.arranger.placementOf(window)
        placement.maximized?.let { window.maximized = it }
        placement.fullscreen?.let { window.fullscreen = it }
        val workspace = desktop.arranger.workspaceFor(placement)
        val monitor = workspace.monitor ?: desktop.focusedMonitor
        if (window.maximized && !window.fullscreen) return desktop.overlayHost.withoutTitle(window, monitor.usableArea).let { it.width to it.height }
        if (window.fullscreen) return monitor.area.let { it.width to it.height }
        if (placement.floating ?: (placement.pinned == true || desktop.arranger.floats(window))) return (placement.width ?: 0) to (placement.height ?: 0)
        val target = desktop.arranger.arrange(workspace, extra = window)[window] ?: return 0 to 0
        window.tiled = true
        return target.width to target.height
    }

    fun windowMapped(window: Window) {
        desktop.lockHost.mapNumbers[window] = ++desktop.lockHost.mapCount
        desktop.pads.scratchpadFor(window)?.let { pad ->
            // Its program was just started (usually by the scratchpad's key): show it. A special
            // workspace's rule sends it there silently (unless goToWorkspace), or it is shown already.
            val placement = desktop.arranger.placementOf(window)
            desktop.arranger.placements -= window
            pad.windows += window
            if (pad.isSpecial && (!pad.shown || pad.leaving) && !placement.goToWorkspace) {
                desktop.layoutLog.info { "$window opened in the special workspace ${pad.name} (hidden)" }
                desktop.events.windowListChanged()
                desktop.changed()
            } else if (pad.isSpecial && (!pad.shown || pad.leaving)) {
                desktop.pads.showSpecial(pad)
            } else {
                desktop.pads.showScratchpad(pad, appearing = setOf(window))
            }
            desktop.windowsLog.info { "Opened $window (workspace ${desktop.workspaceHost.workspaceOf(window)?.number ?: "-"}, ${if (window.tiled) "tiled" else "floating"})" }
            notifyEach(desktop.events.windowOpenedListeners, window, "window-opened")
            return
        }
        val placement = desktop.arranger.placementOf(window)
        desktop.arranger.placements -= window
        val workspace = desktop.arranger.workspaceFor(placement)
        if (desktop.directed(window)) {
            // Its place is the director's: it only joins a workspace (focus, the window list).
            workspace.add(window)
            window.tiled = false
            if (placement.goToWorkspace || placement.focus) desktop.focus(window) else desktop.changed()
            desktop.windowsLog.info { "Opened $window (placed by the director)" }
            notifyEach(desktop.events.windowOpenedListeners, window, "window-opened")
            return
        }
        val monitor = workspace.monitor ?: desktop.focusedMonitor
        val visibleRect = window.visibleRect
        // Its place when floating: its own size, or (if the layout sized it) 2/3 of the monitor.
        val floatingPlace = if (window.tiled) desktop.arranger.floatingPlace(window, placement, monitor, monitor.area.width * 2 / 3, monitor.area.height * 2 / 3)
            else desktop.arranger.floatingPlace(window, placement, monitor, visibleRect.width, visibleRect.height)
        desktop.arranger.floatingRects[window] = floatingPlace
        workspace.add(window)
        val floats = placement.floating ?: (placement.pinned == true || desktop.arranger.floats(window))
        if (floats || workspace.layout === Floating) {
            if (floats) workspace.floating += window
            window.tiled = false
            window.x = floatingPlace.x
            window.y = floatingPlace.y
        }
        if (window.maximized || window.fullscreen) {
            // Started maximized: if it is ever restored, it gets a sensible floating place.
            if (placement.width == null) desktop.arranger.floatingRects[window] = Rect(floatingPlace.x, floatingPlace.y, monitor.area.width * 2 / 3, monitor.area.height * 2 / 3)
            window.x = if (window.fullscreen) monitor.area.x else monitor.usableArea.x
            window.y = if (window.fullscreen) monitor.area.y else monitor.usableArea.y + desktop.titleHeight(window)
        }
        // Floating: within the size limits of its rules (it draws itself anew in that size).
        val behavior = desktop.behaviorOf(window)
        if (!window.tiled && !window.maximized && !window.fullscreen) {
            val (w, h) = behavior.limit(visibleRect.width, visibleRect.height)
            if (w != visibleRect.width || h != visibleRect.height) {
                desktop.arranger.floatingRects[window] = Rect(window.x, window.y, w, h)
                window.configure(w, h)
            }
        }
        if (placement.pinned == true && !window.tiled) desktop.arranger.pinned += window
        // Its own tile was decided before the first frame: it appears there right away.
        val targets = desktop.arranger.arrange(workspace)
        targets[window]?.let { window.x = it.x; window.y = it.y }
        // The keyboard: if it is on screen (or should be shown) and may take it.
        val shown = desktop.monitorList.any { it.workspace === workspace }
        if ((shown || placement.goToWorkspace) && desktop.animator.pick("windowsIn", desktop.animations.windowOpen).millis > 0 && !desktop.locked && behavior.animated) {
            desktop.animator.appearing[window] = System.nanoTime()
        }
        if (placement.goToWorkspace || (shown && placement.focus)) desktop.focus(window) else desktop.changed()
        desktop.windowsLog.info { "Opened $window (workspace ${desktop.workspaceHost.workspaceOf(window)?.number ?: "-"}, ${if (window.tiled) "tiled" else "floating"})" }
        notifyEach(desktop.events.windowOpenedListeners, window, "window-opened")
    }

    fun windowUnmapped(window: Window) {
        desktop.windowsLog.info { "Closed $window" }
        desktop.popupHost.dismissPopupsOf(window, "it closed")
        // Its dialogs belong to its own parent now (or to nobody).
        for (child in desktop.allWindows) if (child.parent === window) child.parentGone(window)
        desktop.arranger.placements -= window
        desktop.animator.appearing -= window
        desktop.arranger.pinned -= window
        // Where the director drew it last: gone too (the pointer looked there before the next frame).
        if (window in desktop.places) desktop.places = desktop.places - window
        // Its last look goes away smoothly (taken just before its picture went, or now).
        desktop.animator.stopFlights(window)
        desktop.animator.appearingPictures.remove(window)?.close()
        (desktop.animator.lastLooks.remove(window) ?: desktop.animator.lastLookOf(window))?.let { desktop.animator.flights += it }
        desktop.ownTitleBars.forget(window)
        desktop.events.minimizeTargets -= window
        // What every closed window leaves behind (a scratchpad's too).
        desktop.arranger.pending.remove(window)
        desktop.arranger.floatingRects -= window
        desktop.animator.glides -= window
        desktop.animator.shownRects -= window
        desktop.animator.screenRects -= window
        desktop.pointerGrab.forget(window)
        if (desktop.pointerInput.hovered === window) desktop.pointerInput.hovered = null
        // (It had the focus even if a launcher, a widget or a panel has the keyboard now.)
        val hadFocus = window === desktop.focuser.lastFocused || window.activated || desktop.seat.keyboardFocus === window.surface
        if (desktop.focuser.lastFocused === window) desktop.focuser.lastFocused = null
        desktop.events.windowListChanged()
        desktop.scratchpadOf(window)?.let { pad ->
            pad.windows -= window
            pad.workspace.model.forget(window)
            // (A special workspace may stay shown empty, if its settings say so; new windows then open in it.)
            val back = pad.focusBefore?.get()?.takeIf { it in desktop.windows }
            if (pad.windows.isEmpty() && (!pad.isSpecial || desktop.specialWorkspaces.hideWhenEmpty)) {
                if (pad.isSpecial && pad.shown) desktop.layoutLog.info { "Special workspace ${pad.name} hidden: its last window closed" }
                pad.shown = false
                pad.leaving = false
                pad.focusBefore = null
                desktop.pads.shownPads -= pad
                desktop.pads.padTimers.remove(pad)?.cancel()
            } else if (pad.shown) {
                desktop.pads.placeScratchpad(pad, appearing = emptySet())
            }
            if (desktop.pads.padFocus === window || hadFocus) desktop.focuser.passFocusOn(pad.windows.lastOrNull()?.takeIf { pad.shown } ?: back ?: desktop.windows.lastOrNull())
            desktop.changed()
            notifyEach(desktop.events.windowClosedListeners, window, "window-closed")
            return
        }
        val workspace = desktop.workspaceHost.workspaceOf(window)
        workspace?.remove(window)
        workspace?.model?.forget(window) // closed for good: its place is free now
        if (workspace != null) {
            desktop.arranger.arrange(workspace)
            desktop.workspaceHost.dropIfEmpty(workspace)
        }
        if (hadFocus) desktop.focuser.passFocusOn(desktop.windows.lastOrNull())
        desktop.changed()
        notifyEach(desktop.events.windowClosedListeners, window, "window-closed")
    }

    // Also for a window that never appeared: its placement (asked before its first frame) and
    // its place in a remembering layout (given to learn its size) go.
    fun windowDestroyed(window: Window) {
        desktop.arranger.placements -= window
        for (workspace in desktop.workspaceHost.workspaces.values) workspace.model.forget(window)
        desktop.pads.allPads.forEach { it.windows -= window }
    }

    fun windowCommitted(window: Window) {
        desktop.animator.lastLooks.remove(window)?.target?.close() // it drew again: still here
        if (window in desktop.arranger.pending) desktop.arranger.applyWhenReady()
        // While resizing from the left or top, the opposite edge stays where it was.
        val resize = desktop.grab as? DesktopPointerGrab.Grab.Resize ?: return
        if (resize.window !== window) return
        val visibleRect = window.visibleRect
        if (resize.edges and XdgToplevel.ResizeEdge.LEFT != 0) window.x = resize.start.right - visibleRect.width
        if (resize.edges and XdgToplevel.ResizeEdge.TOP != 0) window.y = resize.start.bottom - visibleRect.height
        desktop.changed()
    }

    // A request its rules say is not done: the program only hears how its window is.
    private fun refuses(window: Window, request: WindowRequest): Boolean {
        if (request !in desktop.behaviorOf(window).ignored) return false
        desktop.windowsLog.info { "$window asked for ${request.name.lowercase()}: not done (its rules say so)" }
        return true
    }

    // The director handles [request] of [window] itself (true), or leaves it to us.
    private fun directorTakes(window: Window, request: WindowRequest, on: Boolean): Boolean =
        desktop.directed(window) && userCode("Your WindowDirector (requested)", false) { desktop.director!!.requested(window, request, on) }

    fun maximizeRequested(window: Window, maximized: Boolean) {
        if (directorTakes(window, WindowRequest.MAXIMIZE, maximized)) return
        // A scratchpad window already has its place; it only gets an answer.
        if (desktop.scratchpadOf(window) != null || refuses(window, WindowRequest.MAXIMIZE)) window.configure() else setMaximized(window, maximized)
    }

    fun fullscreenRequested(window: Window, fullscreen: Boolean) {
        if (directorTakes(window, WindowRequest.FULLSCREEN, fullscreen)) return
        if (desktop.scratchpadOf(window) != null || refuses(window, WindowRequest.FULLSCREEN)) {
            window.configure()
            return
        }
        // Fullscreen, but the focus only as the rule against focus stealing says (a window on
        // a hidden workspace took the keyboard while the user typed elsewhere).
        applyFullscreen(window, fullscreen, takeFocus = false)
        if (fullscreen && window.mapped) focusRequested(window, WindowRequest.FULLSCREEN)
    }

    fun focusRequested(window: Window, why: WindowRequest) {
        if (desktop.locked) {
            desktop.focusLog.info { "$window asked for the focus ($why) while locked: ignored" }
            return
        }
        if (window === desktop.focusedWindow && desktop.seat.keyboardFocus === window.surface) return
        if (userCode("programMayTakeFocus", false) { desktop.programMayTakeFocus(window, why) }) {
            desktop.focusLog.info { "$window takes the focus ($why)" }
            desktop.focus(window)
        } else if (window !== desktop.focusedWindow) {
            desktop.focusLog.info { "$window asked for the focus ($why); marked urgent instead" }
            setUrgent(window, true)
        }
    }

    // Minimizing a scratchpad window hides its scratchpad.
    fun minimizeRequested(window: Window) {
        if (directorTakes(window, WindowRequest.MINIMIZE, true)) return
        if (refuses(window, WindowRequest.MINIMIZE)) return
        val pad = desktop.scratchpadOf(window)
        if (pad != null) desktop.pads.hideScratchpad(pad) else minimize(window)
    }

    fun setMaximized(window: Window, maximized: Boolean) {
        desktop.windowsLog.info { "$window: ${if (maximized) "maximized" else "not maximized"}" }
        if (!window.configured) {
            window.maximized = maximized // sent with the first configure (see initialSize)
            return
        }
        if (window.maximized == maximized) {
            window.configure() // the app expects an answer anyway
            return
        }
        window.maximized = maximized
        applyState(window)
    }

    internal fun applyFullscreen(window: Window, fullscreen: Boolean, takeFocus: Boolean) {
        desktop.windowsLog.info { "$window: ${if (fullscreen) "fullscreen" else "not fullscreen"}" }
        if (!window.configured) {
            window.fullscreen = fullscreen
            return
        }
        if (window.fullscreen == fullscreen) {
            window.configure()
            return
        }
        window.fullscreen = fullscreen
        applyState(window)
        if (takeFocus && fullscreen && window.mapped) desktop.focus(window)
    }

    // Puts a window where its states say: full desktop, its tile, or its floating place.
    internal fun applyState(window: Window) {
        desktop.arranger.pending.remove(window)
        val workspace = desktop.workspaceHost.workspaceOf(window)
        if (window.maximized || window.fullscreen) {
            if (!window.tiled && window.mapped) desktop.arranger.floatingRects[window] = currentRect(window)
            // Fullscreen covers its monitor; maximized leaves panels and docks free.
            val monitor = desktop.monitorOf(window)
            val area = if (window.fullscreen) monitor.area else desktop.overlayHost.withoutTitle(window, monitor.usableArea)
            desktop.animator.moveSmoothly(window, area.x, area.y)
            window.configure(area.width, area.height)
        } else if (workspace != null && window in workspace.tiledWindows()) {
            window.tiled = false // makes arrange send the tile size again
        } else {
            val floating = desktop.arranger.floatingRects[window] ?: currentRect(window)
            desktop.animator.moveSmoothly(window, floating.x, floating.y)
            window.configure(floating.width, floating.height)
        }
        workspace?.let { desktop.arranger.arrange(it) }
        desktop.changed()
    }

    internal fun currentRect(window: Window): Rect {
        val visibleRect = window.visibleRect
        return Rect(window.x, window.y, visibleRect.width, visibleRect.height)
    }

    fun minimize(window: Window) {
        val workspace = desktop.workspaceHost.workspaceOf(window) ?: return
        if (window !in workspace.windows) return
        desktop.windowsLog.info { "$window: minimized${if (window in desktop.events.minimizeTargets) " (into its dock icon)" else ""}" }
        // Into its dock icon (or down out of sight), as the minimize animation says.
        val shown = desktop.animator.shownRects[window]
        desktop.animator.stopFlights(window)
        if (shown != null) desktop.animator.flightOf(window, DesktopAnimations.FlightKind.MINIMIZE, "minimize", desktop.animations.minimize, to = desktop.events.minimizedRect(window, shown))?.let { desktop.animator.flights += it }
        workspace.windowList -= window
        workspace.minimizedList += window
        window.setMinimized(true)
        desktop.popupHost.dismissPopupsOf(window, "it was minimized")
        desktop.arranger.pending.remove(window)
        desktop.pointerGrab.forget(window)
        desktop.arranger.arrange(workspace)
        // (Only if it had the focus: a background window minimizing itself takes nothing away.)
        if (workspace === desktop.activeWorkspace && (window === desktop.focuser.lastFocused || window.activated)) {
            window.setActivated(false)
            desktop.focuser.passFocusOn(desktop.windows.lastOrNull())
        }
    }

    fun moveRequested(window: Window, serial: Int) {
        if (!dragAllowed(window, serial, "move")) return
        if (desktop.directed(window) && userCode("Your WindowDirector (moveRequested)", false) { desktop.director!!.moveRequested(window) }) return
        desktop.beginMove(window)
    }

    fun resizeRequested(window: Window, serial: Int, edges: Int) {
        if (!dragAllowed(window, serial, "resize")) return
        if (desktop.directed(window) && userCode("Your WindowDirector (resizeRequested)", false) { desktop.director!!.resizeRequested(window, edges) }) return
        desktop.beginResize(window, edges)
    }

    fun x11DragRequested(window: Window, edges: Int?) {
        val what = if (edges == null) "move" else "resize"
        if (desktop.buttonsHeld == 0 || desktop.pointerInput.pressedIn?.surface?.resource?.client !== window.surface.resource.client) {
            desktop.windowsLog.info { "$window asked to $what without a button held in it: refused" }
            return
        }
        if (edges == null) {
            if (desktop.directed(window) && userCode("Your WindowDirector (moveRequested)", false) { desktop.director!!.moveRequested(window) }) return
            desktop.beginMove(window)
        } else {
            if (desktop.directed(window) && userCode("Your WindowDirector (resizeRequested)", false) { desktop.director!!.resizeRequested(window, edges) }) return
            desktop.beginResize(window, edges)
        }
    }

    // A program may start dragging its window only with the button the user holds in it: the
    // press went to that program ([serial] is the press's, or a later event of it), not to another.
    private fun dragAllowed(window: Window, serial: Int, what: String): Boolean {
        val client = window.surface.resource.client
        val pressed = desktop.pointerInput.pressedIn?.surface?.resource?.client
        if (desktop.buttonsHeld > 0 && pressed === client && desktop.seat.isInputSerial(client, serial)) return true
        desktop.windowsLog.info { "$window asked to $what without a button held in it: refused" }
        return false
    }

    fun nearestEdges(window: Window, x: Double, y: Double): Int {
        val rect = currentRect(window)
        val horizontal = if (x < rect.x + rect.width / 2) XdgToplevel.ResizeEdge.LEFT else XdgToplevel.ResizeEdge.RIGHT
        val vertical = if (y < rect.y + rect.height / 2) XdgToplevel.ResizeEdge.TOP else XdgToplevel.ResizeEdge.BOTTOM
        return horizontal or vertical
    }

    fun setUrgent(window: Window, urgent: Boolean) {
        if (window.urgent == urgent) return
        window.urgent = urgent
        desktop.focusLog.info { "$window ${if (urgent) "wants attention" else "no longer wants attention"}" }
        if (window.mapped) notifyEach(desktop.events.windowInfoListeners, window, "window-info-changed")
        desktop.events.windowListChanged()
        desktop.changed()
    }

    fun modalDialogOf(window: Window): Window? {
        var dialog = desktop.allWindows.lastOrNull { it.modal && it.parent === window && it !== window } ?: return null
        // (A dialog's own dialog, and so on — a few levels; parents that point at each other end it.)
        repeat(8) {
            val next = desktop.allWindows.lastOrNull { it.modal && it.parent === dialog && it !== dialog && it !== window } ?: return dialog
            dialog = next
        }
        return dialog
    }
}
