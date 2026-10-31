package dev.caulis.wm

import dev.caulis.core.Monitor
import dev.caulis.core.Output
import dev.caulis.core.cursor.CursorTheme

/**
 * Monitors as the configuration wants them: scale, mode, enabled, and the cursor on them.
 */
internal class RunningMonitors(private val running: Running) {

    // Monitors switched off because the configuration says enabled = false.
    private val disabledByConfig = running.config.monitorSettings.values.filter { !it.enabled }.mapTo(HashSet()) { it.name }

    /** Applies the scale to the desktop, the monitors (their own where set) and the programs. */
    fun applyScale() {
        // Each monitor straight to its scale (one with its own never passes through the
        // usual one: programs would draw twice, and the screen change twice).
        running.desktop.defaultScale = running.config.scale
        for (monitor in running.desktop.monitors) running.desktop.setScale(monitor, running.config.monitorSettings[monitor.name]?.scale ?: running.config.scale)
        running.xwayland?.rescaled()
        running.ipc?.changed()
        running.outputManagement.changed()
        running.log.info { "Scale ${running.config.scale}${running.desktop.monitors.filter { it.scale != running.config.scale }.joinToString("") { ", ${it.name} ${it.scale}" }}" }
        applyCursor() // programs that draw their own cursor get the new size
    }

    /**
     * A monitor came (the backend's): it gets its settings (scale, place) and shows a
     * workspace from now on.
     */
    fun addMonitor(name: String, output: Output?, pixelWidth: Int, pixelHeight: Int): Monitor {
        val settings = running.config.monitorSettings[name]
        val x = settings?.x
        val y = settings?.y
        val monitor = running.desktop.addMonitor(name, output, pixelWidth, pixelHeight, settings?.scale ?: running.config.scale, x, y)
        settings?.transform?.let { running.desktop.setTransform(monitor, it) }
        running.config.monitorAdded.forEach { action -> running.safely("onMonitorAdded") { action(running.config, name) } }
        running.xwayland?.rescaled() // the largest scale may be another now
        running.ipc?.changed() // subscribers hear of the monitors
        running.outputManagement.changed()
        return monitor
    }

    /** A monitor went away (the backend's). */
    fun removeMonitor(monitor: Monitor) {
        running.desktop.removeMonitor(monitor)
        running.config.monitorRemoved.forEach { action -> running.safely("onMonitorRemoved") { action(running.config, monitor.name) } }
        running.xwayland?.rescaled()
        running.ipc?.changed()
        running.outputManagement.changed()
    }

    /**
     * Applies changed monitor settings (mode, scale, turn, place, on or off) to the monitors
     * there are; only what differs changes.
     */
    fun applyMonitorSettings() {
        for (monitor in running.desktop.monitors) {
            val settings = running.config.monitorSettings[monitor.name] ?: continue
            applyMode(monitor, settings)
            settings.scale?.let { running.desktop.setScale(monitor, it) }
            settings.transform?.let { running.desktop.setTransform(monitor, it) }
            running.desktop.moveMonitor(monitor, settings.x, settings.y)
        }
        // Off and on again, as the configuration says now (the last monitor stays: else nothing shows).
        val heads = running.outputManagement.heads
        for (monitor in running.desktop.monitors.toList()) {
            if (running.config.monitorSettings[monitor.name]?.enabled != false) continue
            if (running.desktop.monitors.size <= 1) {
                running.log.warn("Monitor ${monitor.name} stays on: it is the only one (enabled = false is for one of several)")
            } else if (heads.setEnabled(monitor.name, false)) {
                disabledByConfig += monitor.name
                running.log.info { "Monitor ${monitor.name} switched off (enabled = false)" }
            }
        }
        for (name in disabledByConfig.toList()) {
            if (running.config.monitorSettings[name]?.enabled == false) continue
            disabledByConfig -= name
            if (heads.setEnabled(name, true)) running.log.info { "Monitor $name used again" }
        }
        running.rapply.applyMonitorLinks?.invoke()
        running.xwayland?.rescaled()
        running.ipc?.changed()
        running.outputManagement.changed()
    }

    // The mode the configuration asks of [monitor] (mode(width, height, refreshHz)), if it is another.
    private fun applyMode(monitor: Monitor, settings: Caulis.MonitorSettings) {
        val width = settings.width ?: return
        val height = settings.height ?: return
        val control = monitor.control ?: return
        val hz = settings.refreshHz
        val wanted = control.modes.filter { it.width == width && it.height == height }
            .minByOrNull { mode -> hz?.let { Math.abs(mode.refreshMillihertz / 1000.0 - it) } ?: -mode.refreshMillihertz.toDouble() }
            ?: if (control.customModes) dev.caulis.core.MonitorMode(width, height, ((hz ?: 60.0) * 1000).toInt()) else null
        if (wanted == null) {
            running.log.warn("Monitor ${monitor.name} has no mode ${width}x$height (it has ${control.modes.joinToString()})")
            return
        }
        val current = control.currentMode
        if (current != null && current.width == wanted.width && current.height == wanted.height && current.refreshMillihertz == wanted.refreshMillihertz) return
        if (control.setMode(wanted)) running.log.info { "Monitor ${monitor.name}: mode $wanted" } else running.log.warn("Monitor ${monitor.name} could not take the mode $wanted")
    }

    /** Applies the cursor settings (theme, size, hiding). */
    fun applyCursor() {
        val theme = CursorTheme.load(running.config.cursorTheme, running.config.cursorSize)
        if (!theme.found) {
            running.cursorLog.warn("The cursor theme \"${theme.name}\" is not installed; using the system's. " +
                "Installed: ${CursorTheme.available().joinToString()}")
        }
        running.cursorLog.info { "Cursor theme ${theme.name}${if (theme.found) "" else " (not found)"}, ${theme.size} px" }
        running.desktop.cursorTheme = theme
        running.xwayland?.cursorChanged()
        running.desktop.hideCursorWhileTyping = running.config.hideCursorWhileTyping
        running.desktop.hideCursorAfterMillis = running.config.hideCursorAfterSeconds * 1000L
        running.cursorFolder = running.safely("cursor theme files", null) {
            // On disk, once (a theme is tens of megabytes; the runtime folder is in memory).
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache")
            val folder = java.nio.file.Path.of(cache, "caulis", "icons")
            if (theme.exportTo(folder)) folder else null
        }
    }
}
