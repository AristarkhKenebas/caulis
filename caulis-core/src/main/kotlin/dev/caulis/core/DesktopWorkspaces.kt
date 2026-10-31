package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.tiling.Layout
import java.util.TreeMap

/**
 * Workspaces: which there are, which monitor shows which in each mode, switching, moving windows
 * between them.
 */
internal class DesktopWorkspaces(private val desktop: Desktop) {

    internal fun ruleOf(number: Int): WorkspaceRule? = desktop.workspaceRules.lastOrNull { it.number == number }

    // By number; with workspaces of each monitor's own, by number and monitor.
    internal data class WorkspaceKey(val number: Int, val monitor: String?)

    private fun keyFor(number: Int, monitor: Monitor): WorkspaceKey = when {
        number == 0 -> WorkspaceKey(0, monitor.name)
        desktop.workspaceMode == WorkspaceMode.SPANNING || desktop.workspaceMode == WorkspaceMode.SEPARATE -> WorkspaceKey(number, monitor.name)
        else -> WorkspaceKey(number, null)
    }

    internal val workspaces = java.util.TreeMap<WorkspaceKey, Workspace>(compareBy<WorkspaceKey> { it.number }.thenBy { it.monitor ?: "" })

    // The workspace with [number] (of [monitor] when monitors have their own), created when first needed.
    internal fun workspace(number: Int, monitor: Monitor = desktop.focusedMonitor): Workspace {
        val key = keyFor(number, monitor)
        return workspaces.getOrPut(key) {
            val rule = if (number > 0) ruleOf(number) else null
            Workspace(number, rule?.layout ?: desktop.layout, key.monitor).also {
                it.leftOut = desktop::directed
                it.monitor = monitor
                it.name = rule?.name
            }
        }
    }

    // No real monitor yet: only the stand-in.
    private fun onlyStandIn() = desktop.placeholder != null && desktop.monitorList.singleOrNull() === desktop.placeholder

    // Pinned workspaces: the monitor each was first shown on (kept while it is gone empty).
    internal val pinnedHomes = HashMap<Int, String>()

    // Where workspace [number] belongs: its rule's monitor (if plugged in); pinned, also where it was first shown.
    internal fun homeOf(number: Int): Monitor? {
        ruleOf(number)?.monitor?.let { name -> desktop.monitorList.firstOrNull { it.name == name }?.let { return it } }
        if (desktop.workspaceMode == WorkspaceMode.PINNED) {
            pinnedHomes[number]?.let { name -> desktop.monitorList.firstOrNull { it.name == name }?.let { return it } }
        }
        return null
    }

    // A new monitor shows: its own workspace from before (plugged in again), or the first free number.
    internal fun workspaceForNewMonitor(monitor: Monitor): Workspace {
        when (desktop.workspaceMode) {
            WorkspaceMode.SPANNING -> return workspace(desktop.focusedMonitor.workspace.number.coerceAtLeast(1), monitor)
            WorkspaceMode.SEPARATE -> return workspaces.values.firstOrNull { it.homeMonitor == monitor.name && it.lastMonitorName == monitor.name } ?: workspace(1, monitor)
            // (A screen of its own; if it is the primary one, arrangePrimary gives it the workspaces.)
            WorkspaceMode.PRIMARY_ONLY -> return workspace(0, monitor)
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> {}
        }
        val shown = desktop.monitorList.map { it.workspace }.toSet()
        // Workspaces whose home it is, then those it showed before it was unplugged.
        desktop.workspaceRules.firstOrNull { it.monitor == monitor.name && shown.none { s -> s.number == it.number } }?.let { return workspace(it.number, monitor) }
        workspaces.values.firstOrNull { it !in shown && !it.isEmpty && it.lastMonitorName == monitor.name }?.let { return it }
        val number = generateSequence(1) { it + 1 }.first { n ->
            n > desktop.workspaceCount || (shown.none { it.number == n } && workspaces[WorkspaceKey(n, null)]?.isEmpty != false && ruleOf(n)?.monitor.let { it == null || it == monitor.name })
        }
        return workspace(number, monitor)
    }

    // Workspaces of a monitor that went away (each monitor had its own): their windows join the same workspace on [into].
    internal fun mergeWorkspaces(of: Monitor, into: Monitor) {
        for (workspace in workspaces.values.filter { it.homeMonitor == of.name || (it.homeMonitor == null && of === desktop.placeholder) }.toList()) {
            mergeInto(workspace, workspace(workspace.number, into))
        }
    }

    // The windows of [from] join [to]; [from] is gone.
    internal fun mergeInto(from: Workspace, to: Workspace) {
        if (from === to) return
        for (window in from.windows.toList() + from.minimized.toList()) {
            val wasMinimized = window in from.minimized
            val wasFloating = window in from.floating
            from.remove(window)
            from.model.forget(window)
            if (wasMinimized) to.minimizedList += window else to.add(window)
            if (wasFloating) to.floating += window
        }
        workspaces.values.remove(from)
        desktop.arranger.arrange(to)
    }

    // PRIMARY_ONLY: the primary monitor shows the numbered workspaces, every other one a screen of its own.
    internal fun arrangePrimary() {
        if (desktop.workspaceMode != WorkspaceMode.PRIMARY_ONLY || desktop.monitorList.isEmpty() || onlyStandIn()) return
        val primary = desktop.primaryMonitor
        if (primary.workspace.number == 0) {
            // The numbered workspace shown elsewhere (that monitor was primary) comes here; this one's windows join it.
            val own = primary.workspace
            val numbered = desktop.monitorList.map { it.workspace }.firstOrNull { it.number > 0 }
                ?: workspaces.values.firstOrNull { it.number > 0 && it.homeMonitor == null }
                ?: workspace(1, primary)
            desktop.monitorList.firstOrNull { it.workspace === numbered }?.let { other ->
                // (The focus goes along with the workspaces.)
                if (desktop.focusedMonitor === other) moveFocusTo(primary)
                other.workspace = workspace(0, other)
                moveWorkspace(other.workspace, other)
                desktop.arranger.arrange(other.workspace)
            }
            primary.workspace = numbered
            moveWorkspace(numbered, primary)
            numbered.lastMonitorName = primary.name
            mergeInto(own, numbered)
            desktop.arranger.arrange(numbered)
        }
        for (monitor in desktop.monitorList) {
            if (monitor !== primary && monitor.workspace.number > 0) {
                monitor.workspace = workspace(0, monitor)
                moveWorkspace(monitor.workspace, monitor)
                desktop.arranger.arrange(monitor.workspace)
            }
        }
        desktop.changed()
    }

    // Workspaces that always exist are there; names and layouts of rules apply to those there are.
    internal fun applyWorkspaceRules() {
        for (workspace in workspaces.values.toList()) {
            if (workspace.number == 0) continue
            val rule = ruleOf(workspace.number)
            workspace.name = rule?.name
            val wanted = rule?.layout ?: desktop.layout
            // (A layout of the same kind keeps its arrangement.)
            if (wanted !== workspace.layout && (wanted.javaClass != workspace.layout.javaClass || wanted.name != workspace.layout.name)) setLayout(workspace, wanted)
        }
        ensurePersistent()
    }

    internal fun ensurePersistent() {
        if (onlyStandIn()) return
        for (rule in desktop.workspaceRules.filter { it.persistent && it.number <= desktop.workspaceCount }) {
            when (desktop.workspaceMode) {
                WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> for (monitor in desktop.monitorList) workspace(rule.number, monitor)
                WorkspaceMode.PRIMARY_ONLY -> workspace(rule.number, desktop.primaryMonitor)
                else -> workspace(rule.number, homeOf(rule.number) ?: desktop.primaryMonitor)
            }
        }
    }

    fun workspaceNumbers(): List<Int> {
        val own = desktop.workspaceMode == WorkspaceMode.SPANNING || desktop.workspaceMode == WorkspaceMode.SEPARATE
        return workspaces.values.filter { it.number > 0 && (!own || it.homeMonitor == desktop.focusedMonitor.name) }.map { it.number }.distinct().sorted()
    }

    fun workspaceAfter(steps: Int): Int {
        val numbers = (workspaceNumbers() + desktop.activeWorkspace.number).filter { it > 0 }.distinct().sorted()
        if (numbers.isEmpty()) return 1
        val at = numbers.indexOf(desktop.activeWorkspace.number).coerceAtLeast(0)
        return numbers[Math.floorMod(at + steps, numbers.size)]
    }

    fun firstEmptyWorkspace(): Int? = (1..desktop.workspaceCount).firstOrNull { n ->
        val key = keyFor(n, if (desktop.workspaceMode == WorkspaceMode.PRIMARY_ONLY) desktop.primaryMonitor else desktop.focusedMonitor)
        workspaces[key]?.isEmpty != false
    }

    fun switchTo(number: Int) {
        if (number !in 1..desktop.workspaceCount) {
            desktop.layoutLog.info { "There is no workspace $number (workspaceCount is $desktop.workspaceCount)" }
            return
        }
        val before = desktop.activeWorkspace.number
        desktop.pointerGrab.end()
        when (desktop.workspaceMode) {
            WorkspaceMode.SPANNING -> {
                if (number == before) return
                for (monitor in desktop.monitorList) show(workspace(number, monitor), on = monitor)
            }
            WorkspaceMode.SEPARATE -> {
                if (number == before) return
                show(workspace(number, desktop.focusedMonitor), on = desktop.focusedMonitor)
            }
            WorkspaceMode.PRIMARY_ONLY -> {
                val primary = desktop.primaryMonitor
                if (primary.workspace.number == number) {
                    if (desktop.focusedMonitor !== primary) focusMonitor(primary)
                    return
                }
                moveFocusTo(primary)
                show(workspace(number, primary), on = primary)
            }
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> {
                val home = homeOf(number)
                val target = workspace(number, home ?: desktop.focusedMonitor)
                val shownOn = desktop.monitorList.firstOrNull { it.workspace === target }
                if (shownOn != null) {
                    if (shownOn !== desktop.focusedMonitor) {
                        desktop.previousWorkspaceNumber = before
                        focusMonitor(shownOn)
                    }
                    return
                }
                val on = home ?: desktop.focusedMonitor
                moveFocusTo(on)
                show(target, on = on)
            }
        }
        if (before > 0 && before != desktop.activeWorkspace.number) desktop.previousWorkspaceNumber = before
        desktop.focus(desktop.arranger.topWindow(desktop.activeWorkspace))
        desktop.layoutLog.info { "Workspace ${desktop.activeWorkspace.number} (${desktop.activeWorkspace.layout.name})${if (desktop.monitorList.size > 1) " on ${desktop.focusedMonitor.name}" else ""}" }
        notifyEach(desktop.events.workspaceListeners, desktop.activeWorkspace, "workspace")
    }

    // [workspace] goes onto [monitor]: its floating windows keep their place relative to the
    // monitor's corner (and stay on it), maximized and fullscreen ones fit it; tiled ones are
    // arranged by whoever shows it.
    internal fun moveWorkspace(workspace: Workspace, monitor: Monitor) {
        val from = workspace.monitor
        workspace.monitor = monitor
        if (from == null || from === monitor) return
        val dx = monitor.area.x - from.area.x
        val dy = monitor.area.y - from.area.y
        for (window in workspace.windows + workspace.minimized) {
            when {
                window.fullscreen || window.maximized -> if (window.configured) desktop.windowRoles.applyState(window)
                !window.tiled -> {
                    window.x = (window.x + dx).coerceIn(monitor.area.x, (monitor.area.right - 50).coerceAtLeast(monitor.area.x))
                    window.y = (window.y + dy).coerceIn(monitor.area.y, (monitor.area.bottom - 50).coerceAtLeast(monitor.area.y))
                    desktop.arranger.floatingRects[window]?.let { desktop.arranger.floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
                }
            }
        }
    }

    // An empty workspace nobody sees goes away (unless it always exists), as one left empty does.
    internal fun dropIfEmpty(workspace: Workspace) {
        if (workspace.isEmpty && workspace.number > 0 && ruleOf(workspace.number)?.persistent != true && desktop.monitorList.none { it.workspace === workspace }) {
            workspaces.values.remove(workspace)
        }
    }

    // [workspace] is shown on [on] now (the one shown there before is hidden, gone if empty).
    internal fun show(workspace: Workspace, on: Monitor) {
        val previous = on.workspace
        if (previous === workspace) return
        if (desktop.animator.pick("workspaces", desktop.animations.workspaceSwitch).millis > 0 && !desktop.locked && !desktop.inOverview) {
            val direction = if (workspace.number >= previous.number) 1 else -1
            desktop.animator.slides[on] = DesktopAnimations.Slide(previous, workspace, direction, System.nanoTime())
        }
        // Pinned windows go along (they float, keeping their place on the monitor).
        for (window in previous.windows.filter { it in desktop.arranger.pinned }) {
            previous.remove(window)
            previous.model.forget(window)
            workspace.add(window)
            workspace.floating += window
        }
        on.workspace = workspace
        moveWorkspace(workspace, on)
        desktop.popupHost.dismissHiddenGrabs("their workspace is not shown any more")
        workspace.lastMonitorName = on.name
        if (desktop.workspaceMode == WorkspaceMode.PINNED && workspace.number > 0 && !onlyStandIn()) pinnedHomes.putIfAbsent(workspace.number, on.name)
        // Empty workspaces disappear once you leave them (never one on screen), unless they always exist.
        dropIfEmpty(previous)
        desktop.arranger.arrange(workspace)
        desktop.changed()
    }

    fun focusMonitor(monitor: Monitor) {
        if (monitor !in desktop.monitorList) return
        moveFocusTo(monitor)
        desktop.focus(desktop.arranger.topWindow(monitor.workspace))
    }

    fun moveToMonitor(window: Window, monitor: Monitor) {
        val from = desktop.monitorOf(window)
        if (from === monitor) return
        // A floating window keeps its place relative to the monitor.
        if (!window.tiled) {
            window.x = monitor.area.x + (window.x - from.area.x).coerceIn(0, (monitor.area.width - 50).coerceAtLeast(0))
            window.y = monitor.area.y + (window.y - from.area.y).coerceIn(0, (monitor.area.height - 50).coerceAtLeast(0))
        }
        moveToWorkspace(window, monitor.workspace)
        if (window.fullscreen || window.maximized) desktop.windowRoles.applyState(window)
        focusMonitor(monitor)
        desktop.focus(window)
    }

    internal fun moveFocusTo(monitor: Monitor) {
        if (desktop.focusedMonitor === monitor) return
        desktop.focusedMonitor = monitor
        desktop.compositor.outputScale = monitor.scale
        desktop.focusLog.trace { "Monitor ${monitor.name}" }
    }

    fun moveToWorkspace(window: Window, number: Int) {
        if (number !in 1..desktop.workspaceCount) return
        if (workspaceOf(window) == null) return // (a scratchpad's: no workspace is made for it)
        val monitor = when (desktop.workspaceMode) {
            WorkspaceMode.PRIMARY_ONLY -> desktop.primaryMonitor
            WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> desktop.monitorOf(window)
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> homeOf(number) ?: desktop.monitorOf(window)
        }
        moveToWorkspace(window, workspace(number, monitor))
    }

    internal fun moveToWorkspace(window: Window, to: Workspace) {
        val from = workspaceOf(window) ?: return
        if (from === to) return
        desktop.layoutLog.info { "$window moves from workspace ${from.number} to ${to.number}" }
        val wasFloating = window in from.floating
        // A floating window going to another monitor keeps its place relative to that one.
        val fromMonitor = from.monitor
        val toMonitor = to.monitor
        if (!window.tiled && fromMonitor != null && toMonitor != null && fromMonitor !== toMonitor) {
            window.x = toMonitor.area.x + (window.x - fromMonitor.area.x).coerceIn(0, (toMonitor.area.width - 50).coerceAtLeast(0))
            window.y = toMonitor.area.y + (window.y - fromMonitor.area.y).coerceIn(0, (toMonitor.area.height - 50).coerceAtLeast(0))
            desktop.arranger.floatingRects[window]?.let { desktop.arranger.floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
        }
        desktop.popupHost.dismissPopupsOf(window, "it moved to another workspace")
        from.remove(window)
        from.model.forget(window) // it lives on the other workspace now
        desktop.arranger.pending.remove(window)
        to.add(window)
        if (wasFloating) to.floating += window
        window.setActivated(false)
        desktop.arranger.arrange(from)
        desktop.arranger.arrange(to)
        if (from === desktop.activeWorkspace) desktop.focus(desktop.arranger.topWindow(from))
        dropIfEmpty(from)
        desktop.changed()
    }

    fun setLayout(workspace: Workspace, layout: Layout) {
        desktop.layoutLog.info { "Workspace ${workspace.number}: layout ${workspace.layout.name} → ${layout.name}" }
        val wasTiled = workspace.tiledWindows().toSet()
        workspace.layout = layout
        // Windows leaving the layout get their own floating place back.
        for (window in wasTiled - workspace.tiledWindows().toSet()) desktop.arranger.untile(window)
        desktop.arranger.arrange(workspace)
        desktop.changed()
    }

    internal fun workspaceOf(window: Window): Workspace? =
        workspaces.values.firstOrNull { window in it.windows || window in it.minimized }
}
