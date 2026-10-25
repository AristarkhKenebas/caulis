package dev.caulis.wm

import dev.caulis.core.DesktopView
import dev.caulis.core.FrameStats
import dev.caulis.core.Output
import dev.caulis.core.drm.DrmBackend
import dev.caulis.core.drm.DrmOutput
import dev.caulis.event.EventLoop
import dev.caulis.event.Watchdog
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import java.nio.file.Files
import java.nio.file.Path

/** The compositor directly on the screen, started from a text console. */
internal fun runOnScreen(config: Caulis) {
    // A compositor never needs root: devices come from the login session. Running as root
    // would give every program inside it full control of the computer.
    if (Files.getAttribute(Path.of("/proc/self"), "unix:uid") == 0) {
        throw IllegalStateException("Do not run ${config.title} as root; start it as your normal user from a text console.")
    }

    EventLoop().use { loop ->
        DrmBackend.open(loop).use { backend ->
            Running(config, loop, backend.renderer, DEFAULT_WIDTH, DEFAULT_HEIGHT, switchConsole = backend.session::switchTo).use { running ->
                val desktop = running.desktop
                println("${config.title} is running on ${backend.device.path} (${backend.renderer.description})")

                // Monitors: each one becomes a wl_output and a monitor of the desktop, with its own view.
                class Shown(val output: Output, val monitor: dev.caulis.core.Monitor, val view: DesktopView)
                val shown = LinkedHashMap<DrmOutput, Shown>()
                desktop.onChange = { shown.keys.forEach(DrmOutput::requestFrame) }
                backend.enabled = { name -> config.monitorSettings[name]?.enabled != false }
                backend.preferredMode = { name, modes ->
                    config.monitorSettings[name]?.let { wish ->
                        modes.filter { it.width == wish.width && it.height == wish.height }
                            .minByOrNull { mode -> wish.refreshHz?.let { Math.abs(mode.refreshMillihertz / 1000.0 - it) } ?: -mode.refreshMillihertz.toDouble() }
                    }
                }
                backend.onOutputAdded = { monitor ->
                    val mode = monitor.mode!!
                    val edid = monitor.edid
                    val output = Output(
                        running.display, monitor.name, listOfNotNull(edid?.make, edid?.model).joinToString(" ").ifEmpty { monitor.name },
                        mode.width, mode.height, mode.refreshMillihertz,
                        physicalWidthMm = monitor.physicalWidthMm, physicalHeightMm = monitor.physicalHeightMm,
                        make = edid?.make ?: "Unknown", model = edid?.model ?: monitor.name,
                        scale = config.monitorSettings[monitor.name]?.scale ?: config.scale,
                    )
                    val place = running.addMonitor(monitor.name, output, mode.width, mode.height)
                    // Each refresh: draw what changed (if anything), then tell programs
                    // their last frame is on screen.
                    val view = desktop.createView(place)
                    shown[monitor] = Shown(output, place, view)
                    val stats = if (config.stats) FrameStats(monitor.name) else null
                    val present = running.timed(stats, monitor::present)
                    val presentDirect = if (config.directScanout) monitor::presentDirect else null
                    monitor.onFrame = { desktop.refresh(view, present, force = monitor.needsPicture, presentDirect = presentDirect) }
                    monitor.onPresented = { nanos, refreshNanos, sequence, zeroCopy ->
                        stats?.presented(nanos, refreshNanos)
                        // Exact: the kernel's own timestamp of the refresh that showed it.
                        val flags = if (zeroCopy) EXACT_PRESENTATION or WpPresentationFeedback.Kind.ZERO_COPY else EXACT_PRESENTATION
                        desktop.presented(view, nanos, refreshNanos, sequence, flags)
                    }
                }
                backend.onOutputRemoved = { monitor ->
                    shown.remove(monitor)?.let { gone ->
                        desktop.removeView(gone.view)
                        running.removeMonitor(gone.monitor)
                        gone.output.remove()
                    }
                }

                val input = backend.input
                input.onKey = running::key
                input.onPointerMotion = { time, dx, dy -> desktop.pointerMovedBy(time, dx, dy) }
                // Touchscreens and tablets: across the first monitor.
                input.onPointerMotionAbsolute = { time, x, y ->
                    val area = desktop.monitors.first().area
                    desktop.pointerMoved(time, area.x + x * area.width, area.y + y * area.height)
                }
                input.onPointerButton = running::pointerButton
                input.onPointerAxis = running::pointerAxis
                running.inputDevices = input
                input.onGesture = running::gesture

                backend.start()
                if (shown.isEmpty()) println("No monitor is connected yet; waiting for one.")
                println("Programs inside it use WAYLAND_DISPLAY=${running.socket}. Ctrl+Alt+Backspace quits.")
                Watchdog(loop).use { running.runLoop() }
            }
        }
    }
}

// The time comes from the display hardware at the refresh (vsync) itself.
private const val EXACT_PRESENTATION = WpPresentationFeedback.Kind.VSYNC or
    WpPresentationFeedback.Kind.HW_CLOCK or WpPresentationFeedback.Kind.HW_COMPLETION

private const val DEFAULT_WIDTH = 1920
private const val DEFAULT_HEIGHT = 1080
