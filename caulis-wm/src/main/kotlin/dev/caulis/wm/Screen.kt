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

                // Monitors: each one becomes a wl_output and shows the desktop.
                // (For now every monitor shows the same desktop, sized after the first one.)
                val outputs = LinkedHashMap<DrmOutput, Output>()
                val views = HashMap<DrmOutput, DesktopView>()
                desktop.onChange = { outputs.keys.forEach(DrmOutput::requestFrame) }
                fun fitDesktop() {
                    val first = outputs.keys.firstOrNull() ?: return
                    desktop.resizeScreen(first.width, first.height)
                }
                backend.onOutputAdded = { monitor ->
                    val mode = monitor.mode!!
                    val edid = monitor.edid
                    outputs[monitor] = Output(
                        running.display, monitor.name, listOfNotNull(edid?.make, edid?.model).joinToString(" ").ifEmpty { monitor.name },
                        mode.width, mode.height, mode.refreshMillihertz,
                        physicalWidthMm = monitor.physicalWidthMm, physicalHeightMm = monitor.physicalHeightMm,
                        make = edid?.make ?: "Unknown", model = edid?.model ?: monitor.name,
                        scale = config.scale,
                    )
                    fitDesktop()
                    // Each refresh: draw what changed (if anything), then tell programs
                    // their last frame is on screen.
                    val view = desktop.createView()
                    views[monitor] = view
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
                    outputs.remove(monitor)?.remove()
                    views.remove(monitor)?.let(desktop::removeView)
                    fitDesktop()
                }

                val input = backend.input
                input.onKey = running::key
                input.onPointerMotion = { time, dx, dy -> desktop.pointerMoved(time, desktop.pointerX + dx, desktop.pointerY + dy) }
                input.onPointerMotionAbsolute = { time, x, y -> desktop.pointerMoved(time, x * desktop.width, y * desktop.height) }
                input.onPointerButton = running::pointerButton
                input.onPointerAxis = desktop::pointerAxis
                input.onGesture = running::gesture

                backend.start()
                if (outputs.isEmpty()) println("No monitor is connected yet; waiting for one.")
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
