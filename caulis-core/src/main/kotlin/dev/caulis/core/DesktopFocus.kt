package dev.caulis.core

import dev.caulis.tiling.Floating

/**
 * Who has the focus: the window on top, what happens when it goes, moving the focus by direction.
 */
internal class DesktopFocus(private val desktop: Desktop) {

    // Nobody has the keyboard although somebody should: the locker, the newest menu that took
    // it, a panel that keeps it, or else the top window.
    internal fun keyboardLost() {
        if (desktop.keyboardWidget != null) return
        if (desktop.lockHost.sessionLocked) {
            desktop.lockHost.lockSurfaces().firstOrNull { it.surface.resource.isAlive }?.let { desktop.widgetHost.keyboardTo(it.surface) }
            return
        }
        desktop.lockHost.ownLock?.let { own ->
            desktop.focusWidget(own.screen)
            return
        }
        if (desktop.layerHost.layerFocus?.surface?.resource?.isAlive == false) desktop.layerHost.layerFocus = null
        val next = desktop.popupHost.grabs.lastOrNull { it.surface.resource.isAlive }?.surface
            ?: desktop.layerHost.exclusiveLayer()?.surface?.takeIf { it.resource.isAlive }
            ?: desktop.layerHost.layerFocus?.surface
        desktop.focusLog.info { "The keyboard's surface went away: it goes to ${next?.roleObject ?: desktop.windows.lastOrNull { it.surface.resource.isAlive } ?: "nobody"}" }
        if (next != null) desktop.widgetHost.keyboardTo(next) else focus(desktop.windows.lastOrNull { it.surface.resource.isAlive })
    }

    internal var lastFocused: Window? = null

    fun cycleFocus() {
        val next = desktop.minimized.firstOrNull() ?: desktop.windows.firstOrNull() ?: return
        if (next === desktop.windows.lastOrNull()) return // only one window
        focus(next)
    }

    fun focusDirection(dx: Int, dy: Int) {
        val from = desktop.focusedWindow
        if (from == null) {
            // Nothing focused here: the monitor beside, if there is one.
            val beside = desktop.monitorBeside(dx, dy)
            if (beside != null) desktop.focusMonitor(beside) else focus(desktop.windows.lastOrNull())
            return
        }
        val origin = desktop.currentRect(from)
        // Windows on every monitor count: past the last one here comes the next monitor.
        val shown = desktop.stackingOrder()
        val candidates = shown.filter { it !== from }.map { it to desktop.currentRect(it) }.filter { (_, rect) ->
            (dx > 0 && rect.x >= origin.right - 1) || (dx < 0 && rect.right <= origin.x + 1) ||
                (dy > 0 && rect.y >= origin.bottom - 1) || (dy < 0 && rect.bottom <= origin.y + 1)
        }
        val nearest = candidates.minByOrNull { (_, rect) ->
            val distanceX = (rect.x + rect.width / 2) - (origin.x + origin.width / 2)
            val distanceY = (rect.y + rect.height / 2) - (origin.y + origin.height / 2)
            distanceX.toLong() * distanceX + distanceY.toLong() * distanceY
        }
        if (nearest != null) focus(nearest.first) else desktop.monitorBeside(dx, dy)?.let(desktop::focusMonitor) // an empty monitor
    }

    fun closeFocused() {
        desktop.focusedWindow?.close()
    }

    // When the focus last went to another window (System.nanoTime).
    internal var focusedAt = 0L

    // The focused window went (closed, minimized): [next] gets the focus — the keyboard too if
    // a window had it; a launcher, a widget or a panel that has it keeps it (and gives it to
    // [next] when done). Nothing while locked (unlocking gives the top window the focus).
    internal fun passFocusOn(next: Window?) {
        if (desktop.locked) return
        if (desktop.keyboardWidget == null && desktop.layerHost.exclusiveLayer() == null && desktop.layerHost.layerFocus == null) return focus(next)
        val pad = next?.let(desktop::scratchpadOf)
        for (other in desktop.activeWorkspace.windowList) if (other !== next) other.setActivated(false)
        for (other in desktop.pads.allPads.flatMap { it.windows }) if (other !== next) other.setActivated(false)
        if (next != null) {
            if (pad == null && desktop.activeWorkspace.windowList.remove(next)) desktop.activeWorkspace.windowList += next
            next.setActivated(true)
        }
        desktop.pads.padFocus = next?.takeIf { pad != null }
        if (next !== lastFocused) {
            lastFocused = next
            focusedAt = System.nanoTime()
            desktop.focusLog.info { "Focus: ${next ?: "nothing"} (the keyboard stays where it is)" }
            notifyEach(desktop.events.focusListeners, next, "focus")
        }
        desktop.changed()
    }

    // Somebody should have the keyboard back (a menu closed): the newest menu that took it,
    // the panel that keeps it or was clicked, else the focused window (a scratchpad's too).
    internal fun restoreKeyboard() {
        if (desktop.keyboardWidget != null) return
        if (desktop.locked) return keyboardLost()
        desktop.widgetHost.keyboardTo(desktop.popupHost.grabs.lastOrNull()?.surface ?: desktop.layerHost.exclusiveLayer()?.surface ?: desktop.layerHost.layerFocus?.surface ?: desktop.focusedWindow?.surface)
    }

    fun focus(window: Window?) {
        // The locker has the keyboard while the session is locked; no window takes it (nor
        // raises itself, nor switches workspaces) behind the lock — but one the compositor's own
        // lock screen allows (a lock screen that is a program's window).
        if (desktop.locked && (window == null || !desktop.lockHost.lockAllows(window.surface))) {
            desktop.focusLog.info { "Focus to ${window ?: "nothing"} ignored: the session is locked" }
            return
        }
        // A window waiting for a modal dialog of its own: the dialog gets the focus (an IDE's
        // settings; else keys seemed to do nothing) — unless a rule says it never takes it (then
        // the focus went back to the window, and round again).
        if (window != null) desktop.modalDialogOf(window)?.takeIf { desktop.behaviorOf(it).takesFocus }?.let { return focus(it) }
        // A window getting the focus takes the keyboard back from a widget.
        if (window != null && desktop.keyboardWidget != null) desktop.focusWidget(null)
        // One that never takes the keyboard: the top one of its workspace that does, instead.
        if (window != null && !desktop.behaviorOf(window).takesFocus) {
            val instead = desktop.workspaceOf(window)?.windows?.lastOrNull { it !== window && desktop.behaviorOf(it).takesFocus }
            if (instead === window || (instead == null && desktop.focusedWindow != null && desktop.workspaceOf(desktop.focusedWindow!!) === desktop.workspaceOf(window))) return
            return focus(instead)
        }
        if (window != null && window.urgent) desktop.setUrgent(window, false)
        val pad = window?.let(desktop::scratchpadOf)
        // Everything else loses the "active" look.
        for (other in desktop.pads.allPads.flatMap { it.windows }) if (other !== window) other.setActivated(false)
        // A window of a hidden special workspace (from a task bar): the special workspace shows.
        if (pad != null && pad.isSpecial && (!pad.shown || pad.leaving)) {
            desktop.pads.showScratchpad(pad, appearing = pad.windows.toSet(), focusOn = window)
            return
        }
        if (pad != null) {
            for (other in desktop.activeWorkspace.windows) other.setActivated(false)
            if (pad.isSpecial) desktop.pads.lastSpecial = pad
            pad.windows -= window
            pad.windows += window
            window.setActivated(true)
            desktop.pads.padFocus = window
            if (desktop.layerHost.exclusiveLayer() == null) {
                desktop.layerHost.layerFocus = null
                desktop.widgetHost.keyboardTo(window.surface)
            }
            if (window !== lastFocused) {
                lastFocused = window
                focusedAt = System.nanoTime()
                desktop.focusLog.info { "Focus: $window" }
                notifyEach(desktop.events.focusListeners, window, "focus")
            }
            desktop.changed()
            return
        }
        desktop.pads.padFocus = null
        if (window != null) {
            val workspace = desktop.workspaceOf(window)
            if (workspace != null && workspace !== desktop.activeWorkspace) {
                // Its monitor gets the focus; a workspace on no monitor comes onto the focused one.
                val showing = desktop.monitorList.firstOrNull { it.workspace === workspace }
                if (showing != null) {
                    desktop.setFocusedMonitor(showing)
                } else {
                    // Where this workspace belongs, as the workspace mode says (as switchTo does).
                    when {
                        desktop.workspaceMode == WorkspaceMode.SPANNING && workspace.number > 0 ->
                            for (monitor in desktop.monitorList) desktop.show(desktop.workspace(workspace.number, monitor), on = monitor)
                        else -> {
                            val on = desktop.arranger.monitorNamed(workspace.homeMonitor)
                                ?: (if (desktop.workspaceMode == WorkspaceMode.PRIMARY_ONLY && workspace.number > 0) desktop.primaryMonitor else null)
                                ?: (if (workspace.number > 0) desktop.homeOf(workspace.number) else null)
                                ?: desktop.focusedMonitor
                            desktop.setFocusedMonitor(on)
                            desktop.show(workspace, on = on)
                        }
                    }
                    desktop.layoutLog.info { "Workspace ${workspace.number} (${workspace.layout.name})" }
                    notifyEach(desktop.events.workspaceListeners, workspace, "workspace")
                }
            }
            if (desktop.activeWorkspace.minimizedList.remove(window)) {
                if (window !in desktop.activeWorkspace.windowList) desktop.activeWorkspace.windowList += window
                window.setMinimized(false)
                // Back out of its dock icon (or up from below), as the restore animation says.
                desktop.animator.stopFlights(window)
                desktop.animator.glides -= window
                desktop.animator.flightOf(window, DesktopAnimations.FlightKind.RESTORE, "restore", desktop.animations.restore, to = desktop.events.minimizedRect(window, desktop.currentRect(window)))?.let { desktop.animator.flights += it }
            }
        }
        val list = desktop.activeWorkspace.windowList
        // Every other window is not the active one any more — also those on other monitors'
        // workspaces (a window clicked on the second monitor left the first one's looking
        // focused, and some programs then acted as if they still had the keys).
        for (other in desktop.allWindows) if (other !== window && other.activated) other.setActivated(false)
        if (window != null) {
            // On top of its own workspace (only if it is on this one: never into another's list).
            if (list.remove(window)) list += window
            window.setActivated(true)
        }
        if (desktop.layerHost.exclusiveLayer() == null) {
            desktop.layerHost.layerFocus = null
            desktop.widgetHost.keyboardTo(window?.surface)
        }
        if (window !== lastFocused) {
            lastFocused = window
            focusedAt = System.nanoTime()
            desktop.focusLog.info { "Focus: ${window ?: "nothing"}" }
            notifyEach(desktop.events.focusListeners, window, "focus")
        }
        // Layouts that follow the focus (scrolling) move their windows.
        if (window != null && window.tiled && desktop.activeWorkspace.layout !== Floating) desktop.arranger.arrange(desktop.activeWorkspace)
        desktop.changed()
    }
}
