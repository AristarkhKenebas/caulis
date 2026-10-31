package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Monitors coming and going, and their size, place, scale, turning and power.
 */
internal class DesktopMonitors(private val desktop: Desktop) {

    fun setBackgroundView(monitor: Monitor, view: BackgroundView?) {
        if (desktop.backdrops.views[monitor.name] == view) return
        if (view == null) desktop.backdrops.views -= monitor.name else desktop.backdrops.views[monitor.name] = view
        desktop.changed()
    }

    fun setScale(monitor: Monitor, value: Double) {
        require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
        if (value == monitor.scale) return
        desktop.layoutLog.info { "Monitor ${monitor.name}: scale ${monitor.scale} → $value" }
        desktop.scaleFade.fadeFromNow(monitor) // the old picture melts into the new one instead of jumping
        monitor.scale = value
        monitor.output?.rescale(value)
        if (monitor === desktop.focusedMonitor) desktop.compositor.outputScale = value
        placeMonitors() // its size in units changed: the ones beside it move
        for (each in desktop.monitorList) monitorChanged(each)
        desktop.cursors.cursorChanged()
    }

    fun addMonitor(name: String, output: Output?, pixelWidth: Int, pixelHeight: Int, scale: Double = desktop.defaultScale, x: Int? = null, y: Int? = null): Monitor {
        val monitor = Monitor(name, output, pixelWidth, pixelHeight, scale)
        if (x != null && y != null) monitor.fixedPosition = x to y
        val standIn = desktop.placeholder
        if (standIn != null) {
            // The first real monitor takes over from the stand-in: its workspace and views.
            desktop.placeholder = null
            desktop.monitorList -= standIn
            monitor.workspace = standIn.workspace
            for (view in desktop.views + desktop.capturing.captureViews) if (view.monitor === standIn) view.monitor = monitor
            for (workspace in desktop.workspaceHost.workspaces.values) if (workspace.monitor === standIn) workspace.monitor = monitor
            if (desktop.workspaceMode == WorkspaceMode.SPANNING || desktop.workspaceMode == WorkspaceMode.SEPARATE) {
                // Workspaces of their own on this monitor from now on (the stand-in's windows go along).
                desktop.monitorList += monitor
                desktop.focusedMonitor = monitor
                desktop.workspaceHost.mergeWorkspaces(of = standIn, into = monitor)
                desktop.monitorList -= monitor
                monitor.workspace = desktop.workspaceHost.workspace(monitor.workspace.number, monitor)
            }
        } else {
            monitor.workspace = desktop.workspaceHost.workspaceForNewMonitor(monitor)
        }
        monitor.workspace.monitor = monitor
        desktop.monitorList += monitor
        if (standIn != null) desktop.focusedMonitor = monitor
        // The primary monitor gets the focus when it comes before anything is open.
        if (monitor.name == desktop.primaryMonitorName && desktop.allWindows.isEmpty()) desktop.focusedMonitor = monitor
        output?.rescale(scale)
        desktop.workspaceHost.arrangePrimary()
        desktop.workspaceHost.ensurePersistent()
        if (desktop.workspaceMode == WorkspaceMode.PINNED) for (each in desktop.monitorList) if (each.workspace.number > 0) desktop.workspaceHost.pinnedHomes.putIfAbsent(each.workspace.number, each.name)
        desktop.layoutLog.info { "Monitor ${monitor.name}: ${pixelWidth}x$pixelHeight, scale $scale, workspace ${monitor.workspace.number}" }
        placeMonitors()
        for (each in desktop.monitorList) monitorChanged(each)
        return monitor
    }

    fun removeMonitor(monitor: Monitor) {
        if (monitor !in desktop.monitorList) return
        desktop.scaleFade.dropFade(monitor)
        desktop.backdrops.forget(monitor.name)
        if (desktop.monitorList.size == 1) {
            // The last one: it stays as the stand-in, so windows keep a place until a monitor comes.
            desktop.placeholder = monitor
            return
        }
        desktop.monitorList -= monitor
        val home = if (desktop.focusedMonitor === monitor) desktop.monitorList.first() else desktop.focusedMonitor
        desktop.layoutLog.info { "Monitor ${monitor.name} is gone; its windows stay on their workspaces (now on ${home.name})" }
        for (view in desktop.views + desktop.capturing.captureViews) if (view.monitor === monitor) view.monitor = home
        // Scratchpads shown on it come over to [home]; a special workspace only if none is shown
        // there (one a monitor: else two dimmings and two sets of windows on top of each other).
        for (pad in desktop.pads.shownPads.toList()) if (pad.monitor === monitor && !pad.leaving) {
            if (pad.isSpecial && desktop.pads.shownPads.any { it !== pad && it.isSpecial && !it.leaving && it.monitor === home }) {
                desktop.layoutLog.info { "Special workspace ${pad.name} hidden: its monitor is gone and ${home.name} shows another one" }
                desktop.pads.hideScratchpad(pad, focusBack = false)
            } else {
                pad.monitor = home
            }
        }
        when (desktop.workspaceMode) {
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> for (workspace in desktop.workspaceHost.workspaces.values.toList()) if (workspace.monitor === monitor) desktop.workspaceHost.moveWorkspace(workspace, home)
            WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> desktop.workspaceHost.mergeWorkspaces(of = monitor, into = home)
            WorkspaceMode.PRIMARY_ONLY -> {
                val shown = monitor.workspace
                val primary = desktop.primaryMonitor
                if (shown.number == 0) {
                    // A screen of its own: its windows go to what the primary monitor shows.
                    desktop.workspaceHost.mergeInto(shown, primary.workspace)
                } else {
                    // The primary monitor went: the next one shows its workspace (its own screen's windows join).
                    val own = primary.workspace
                    primary.workspace = shown
                    desktop.workspaceHost.moveWorkspace(shown, primary)
                    if (own.number == 0) desktop.workspaceHost.mergeInto(own, shown)
                    desktop.arranger.arrange(shown)
                }
            }
        }
        // Panels on it close (programs like Waybar open new ones for the monitors there are).
        for (layer in desktop.layerHost.layerSurfaces.filter { desktop.monitorOf(it) === monitor }.toList()) {
            layer.close()
            desktop.layerHost.layerMonitors -= layer
        }
        if (desktop.focusedMonitor === monitor) {
            desktop.focusedMonitor = home
            desktop.compositor.outputScale = home.scale
            desktop.focus(home.workspace.windows.lastOrNull())
        }
        placeMonitors()
        for (each in desktop.monitorList) monitorChanged(each)
    }

    fun setTransform(monitor: Monitor, transform: Transform) {
        if (transform == monitor.transform) return
        val swap = transform.sideways != monitor.transform.sideways
        monitor.transform = transform
        monitor.output?.setTransform(transform)
        desktop.layoutLog.info { "Monitor ${monitor.name} turned: $transform" }
        if (swap) resizeMonitor(monitor, monitor.pixelHeight, monitor.pixelWidth) else for (each in desktop.monitorList) monitorChanged(each)
        desktop.changed()
    }

    fun setPower(monitor: Monitor, on: Boolean): Boolean {
        if (monitor.powered == on) return true
        val done = monitor.control?.setPower(on) ?: false
        if (done) {
            monitor.powered = on
            desktop.layoutLog.info { "Monitor ${monitor.name} ${if (on) "on" else "off"}" }
            if (on) for (view in desktop.views) if (view.monitor === monitor) view.damageEverything()
            desktop.changed()
            desktop.events.powerListeners.toList().forEach { it(monitor) }
        }
        return done
    }

    fun moveMonitor(monitor: Monitor, x: Int?, y: Int?) {
        val place = if (x != null && y != null) x to y else null
        if (place == monitor.fixedPosition) return // (windows are not laid out again for nothing)
        monitor.fixedPosition = place
        placeMonitors()
        for (each in desktop.monitorList) monitorChanged(each)
    }

    fun resizeMonitor(monitor: Monitor, pixelWidth: Int, pixelHeight: Int) {
        if (pixelWidth == monitor.pixelWidth && pixelHeight == monitor.pixelHeight) return
        monitor.pixelWidth = pixelWidth
        monitor.pixelHeight = pixelHeight
        monitor.output?.resize(pixelWidth, pixelHeight)
        placeMonitors()
        for (each in desktop.monitorList) monitorChanged(each)
    }

    // Monitors with a position of their own keep it; the others follow in a row to the right.
    private fun placeMonitors() {
        var nextX = desktop.monitorList.filter { it.fixedPosition != null }.maxOfOrNull { it.fixedPosition!!.first + it.units(it.pixelWidth) } ?: 0
        for (monitor in desktop.monitorList) {
            val (x, y) = monitor.fixedPosition ?: (nextX to 0).also { nextX += monitor.units(monitor.pixelWidth) }
            monitor.area = Rect(x, y, monitor.units(monitor.pixelWidth), monitor.units(monitor.pixelHeight))
            monitor.output?.position(x, y)
        }
    }

    // A monitor's size, place or scale changed: its panels, windows and views follow.
    private fun monitorChanged(monitor: Monitor) {
        desktop.layerHost.arrangeLayers(monitor, refit = false)
        refitWindows(monitor)
        for (view in desktop.views + desktop.capturing.captureViews) if (view.monitor === monitor) view.damageEverything()
    }

    // All monitors together: the box around them.
    internal fun desktopBounds(): Rect = desktop.monitorList.map { it.area }.reduce(Rect::union)

    // The monitor a lock surface covers (its output's, else the first).
    internal fun lockMonitor(surface: LockSurface): Monitor =
        surface.output?.let { output -> desktop.monitorList.firstOrNull { it.output === output } } ?: desktop.monitorList.first()

    internal fun distance(area: Rect, x: Double, y: Double): Double {
        val dx = maxOf(area.x - x, 0.0, x - (area.right - 1))
        val dy = maxOf(area.y - y, 0.0, y - (area.bottom - 1))
        return dx * dx + dy * dy
    }

    // A monitor changed size: maximized and fullscreen windows on it follow, layouts re-arrange.
    internal fun refitWindows(monitor: Monitor) {
        desktop.lockHost.lock?.surfaces?.filter { lockMonitor(it) === monitor }?.forEach { it.configure(monitor.area.width, monitor.area.height) }
        for (pad in desktop.pads.shownPads) if (pad.monitor === monitor) desktop.pads.placeScratchpad(pad, appearing = emptySet())
        for (workspace in desktop.workspaceHost.workspaces.values) {
            if ((workspace.monitor ?: desktop.focusedMonitor) !== monitor) continue
            for (window in workspace.windows + workspace.minimized) {
                if (!window.configured) continue
                if (window.fullscreen) {
                    window.configure(monitor.area.width, monitor.area.height)
                    desktop.animator.moveSmoothly(window, monitor.area.x, monitor.area.y)
                } else if (window.maximized) {
                    val area = desktop.overlayHost.withoutTitle(window, monitor.usableArea)
                    window.configure(area.width, area.height)
                    desktop.animator.moveSmoothly(window, area.x, area.y)
                }
            }
            desktop.arranger.arrange(workspace)
        }
        desktop.changed()
    }

    fun monitorBeside(dx: Int, dy: Int, from: Monitor = desktop.focusedMonitor): Monitor? {
        val cx = from.area.x + from.area.width / 2.0
        val cy = from.area.y + from.area.height / 2.0
        return desktop.monitorList.filter { it !== from }.filter { other ->
            val ox = other.area.x + other.area.width / 2.0
            val oy = other.area.y + other.area.height / 2.0
            (dx > 0 && ox > cx) || (dx < 0 && ox < cx) || (dy > 0 && oy > cy) || (dy < 0 && oy < cy)
        }.minByOrNull { other ->
            val ox = other.area.x + other.area.width / 2.0 - cx
            val oy = other.area.y + other.area.height / 2.0 - cy
            ox * ox + oy * oy
        }
    }
}
