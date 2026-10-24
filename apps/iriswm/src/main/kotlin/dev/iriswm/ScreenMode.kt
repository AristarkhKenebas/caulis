package dev.iriswm

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

/** irisWM directly on the screen, started from a text console. */
fun runOnScreen(options: Options) {
    // A compositor never needs root: devices come from the login session. Running as root
    // would give every program inside it full control of the computer.
    if (Files.getAttribute(Path.of("/proc/self"), "unix:uid") == 0) {
        throw IllegalStateException("Do not run irisWM as root; start it as your normal user from a text console.")
    }

    EventLoop().use { loop ->
        DrmBackend.open(loop).use { backend ->
            IrisCompositor(loop, backend.renderer, DEFAULT_WIDTH, DEFAULT_HEIGHT).use { iris ->
                val desktop = iris.desktop
                println("irisWM is running on ${backend.device.path} (${backend.renderer.description})")

                // Monitors: each one becomes a wl_output and shows the desktop.
                // (For now every monitor shows the same desktop, sized after the first one.)
                val outputs = LinkedHashMap<DrmOutput, Output>()
                val views = HashMap<DrmOutput, DesktopView>()
                desktop.onChange = { outputs.keys.forEach(DrmOutput::requestFrame) }
                fun fitDesktop() {
                    val first = outputs.keys.firstOrNull() ?: return
                    desktop.width = first.width
                    desktop.height = first.height
                }
                backend.onOutputAdded = { monitor ->
                    val mode = monitor.mode!!
                    val edid = monitor.edid
                    outputs[monitor] = Output(
                        iris.display, monitor.name, listOfNotNull(edid?.make, edid?.model).joinToString(" ").ifEmpty { monitor.name },
                        mode.width, mode.height, mode.refreshMillihertz,
                        physicalWidthMm = monitor.physicalWidthMm, physicalHeightMm = monitor.physicalHeightMm,
                        make = edid?.make ?: "Unknown", model = edid?.model ?: monitor.name,
                    )
                    fitDesktop()
                    // Each refresh: draw what changed (if anything), then tell programs
                    // their last frame is on screen.
                    val view = desktop.createView()
                    views[monitor] = view
                    val stats = if (options.stats) FrameStats(monitor.name) else null
                    val present = timed(stats, monitor::present)
                    monitor.onFrame = { desktop.refresh(view, present, force = monitor.needsPicture) }
                    monitor.onPresented = { nanos, refreshNanos, sequence ->
                        stats?.presented(nanos, refreshNanos)
                        // Exact: the kernel's own timestamp of the refresh that showed it.
                        desktop.presented(view, nanos, refreshNanos, sequence, EXACT_PRESENTATION)
                    }
                }
                backend.onOutputRemoved = { monitor ->
                    outputs.remove(monitor)?.remove()
                    views.remove(monitor)?.let(desktop::removeView)
                    fitDesktop()
                }

                val shortcuts = Shortcuts(
                    quit = loop::stop,
                    openTerminal = { iris.launch(options.terminal) },
                    switchConsole = backend.session::switchTo,
                )
                val input = backend.input
                input.onKey = { time, code, pressed -> if (!shortcuts.key(code, pressed)) desktop.key(time, code, pressed) }
                input.onPointerMotion = { time, dx, dy -> desktop.pointerMoved(time, desktop.pointerX + dx, desktop.pointerY + dy) }
                input.onPointerMotionAbsolute = { time, x, y -> desktop.pointerMoved(time, x * desktop.width, y * desktop.height) }
                input.onPointerButton = desktop::pointerButton
                input.onPointerAxis = desktop::pointerAxis

                backend.start()
                if (outputs.isEmpty()) println("No monitor is connected yet; waiting for one.")
                println("Programs inside it use WAYLAND_DISPLAY=${iris.socket}. Super+Enter opens ${options.terminal}; Ctrl+Alt+Backspace quits.")

                options.exitAfterSeconds?.let { seconds -> loop.after(seconds * 1000) { loop.stop() } }
                options.run.forEach(iris::launch)
                Watchdog(loop).use { loop.run() }
            }
        }
    }
}

// The time comes from the display hardware at the refresh (vsync) itself.
private const val EXACT_PRESENTATION = WpPresentationFeedback.Kind.VSYNC or
    WpPresentationFeedback.Kind.HW_CLOCK or WpPresentationFeedback.Kind.HW_COMPLETION

private const val DEFAULT_WIDTH = 1920
private const val DEFAULT_HEIGHT = 1080
