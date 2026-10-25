package dev.caulis.wm

import dev.caulis.core.DesktopView
import dev.caulis.core.FrameStats
import dev.caulis.core.Monitor
import dev.caulis.core.Output
import dev.caulis.core.nested.NestedWindow
import dev.caulis.event.EventLoop
import dev.caulis.render.gles.GlesRenderer

/**
 * The compositor as a window inside the current Wayland session — or as several windows,
 * each one a monitor of its own ([Caulis.nestedMonitors]), to try a desktop with several.
 */
internal fun runNested(config: Caulis) {
    EventLoop().use { loop ->
        GlesRenderer.create().use { renderer ->
            val count = config.nestedMonitors.coerceIn(1, 8)
            val windows = (1..count).map { n ->
                val title = if (count == 1) config.title else "${config.title} — NESTED-$n"
                NestedWindow(loop, renderer, title = title, appId = config.appId, outerSocket = config.nestedIn)
            }
            try {
                val first = windows.first()
                Running(config, loop, renderer, first.width, first.height, switchConsole = null).use { running ->
                    val desktop = running.desktop
                    println("${config.title} is running${if (count > 1) " in $count windows (monitors)" else ""}. Start programs inside it with: WAYLAND_DISPLAY=${running.socket} <program>")
                    println("Renderer: ${renderer.description}")
                    println(if (first.sharesGpuBuffers) "Frames go to the outer session as GPU buffers" else "Frames are copied to the outer session")

                    val stats = ArrayList<FrameStats>()
                    windows.forEachIndexed { index, window ->
                        val name = "NESTED-${index + 1}"
                        val settings = config.monitorSettings[name]
                        val output = Output(running.display, name, "${config.title} (nested)", window.width, window.height, 60_000, scale = settings?.scale ?: config.scale)
                        val monitor = running.addMonitor(name, output, window.width, window.height)
                        val view = desktop.createView(monitor)
                        connect(window, monitor, view, running, config, stats)
                    }
                    desktop.onChange = { windows.forEach(NestedWindow::requestFrame) }

                    running.runLoop()
                    stats.forEach(FrameStats::close)
                }
            } finally {
                windows.forEach(NestedWindow::close)
            }
        }
    }
}

// One window, one monitor: its input, its frames, its size.
private fun connect(window: NestedWindow, monitor: Monitor, view: DesktopView, running: Running, config: Caulis, stats: MutableList<FrameStats>) {
    val desktop = running.desktop
    // Input from the outer session goes to the window manager; the window's pixels are this
    // monitor's, so they are desktop units from its corner.
    window.onPointerMotion = { time, x, y -> desktop.pointerMoved(time, monitor.area.x + x / monitor.scale, monitor.area.y + y / monitor.scale) }
    window.onPointerButton = running::pointerButton
    window.onPointerAxis = running::pointerAxis
    window.onGesture = running::gesture
    window.onKey = running::key
    window.onKeyboardLeave = {
        running.releaseKeys()
        running.seat.releaseAllKeys(now())
    }
    window.onClose = running::quit
    window.onResize = { width, height -> desktop.resizeMonitor(monitor, width, height) }

    // Each refresh of the outer session: draw what changed (if anything), then tell programs
    // their last frame is on screen.
    val frameStats = if (config.stats) FrameStats(monitor.name).also(stats::add) else null
    val present = running.timed(frameStats, window::present)
    window.onFrame = { desktop.refresh(view, present) }
    window.onPresented = { nanos ->
        frameStats?.presented(nanos, refreshNanos = 0)
        desktop.presented(view, nanos, refreshNanos = 0, sequence = 0, flags = 0)
    }
}
