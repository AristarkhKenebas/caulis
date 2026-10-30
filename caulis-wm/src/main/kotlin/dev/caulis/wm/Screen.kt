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
                // Monitors showing another's picture (a projector mirroring the laptop's screen).
                class Mirrored(val view: DesktopView, val mirror: dev.caulis.core.Mirror)
                val mirrored = LinkedHashMap<DrmOutput, Mirrored>()
                desktop.onChange = {
                    shown.keys.forEach(DrmOutput::requestFrame)
                    mirrored.keys.forEach(DrmOutput::requestFrame)
                }
                running.wakeMonitors = { backend.wake() }

                // The laptop's display modes (its key, a projector plugged in).
                fun identity(name: String, edid: dev.caulis.core.drm.Edid?) = edid?.let { "${it.make} ${it.model} ${it.serial}".trim() } ?: name
                val displays = Displays(running, loop, object : Displays.Screens {
                    override fun plugged() = backend.plugged().map { Displays.Screen(it.name, identity(it.name, it.edid), it.output != null) }
                    override fun setEnabled(name: String, on: Boolean) {
                        backend.setEnabled(name, on)
                    }
                    override fun mirroring(name: String) = mirrored.keys.any { it.name == name }
                })
                running.displays = displays
                // A monitor's link (bits per colour, adaptive sync) as the configuration says, with its next mode set.
                fun applyLink(monitor: DrmOutput) {
                    val wish = running.config.monitorSettings[monitor.name]
                    monitor.maxBpc = wish?.maxBpc ?: 8
                    monitor.adaptiveSync = wish?.adaptiveSync ?: running.config.adaptiveSync
                }
                running.applyMonitorLinks = { shown.keys.forEach(::applyLink) }
                backend.onPluggedChanged = { now -> displays.pluggedChanged(now.map { Displays.Screen(it.name, identity(it.name, it.edid), it.output != null) }) }
                backend.enabled = { name -> config.monitorSettings[name]?.enabled != false && displays.wants(name) }
                fun mirrorSource() = shown.values.firstOrNull { config.isBuiltIn(it.monitor.name) }
                backend.preferredMode = { name, modes ->
                    // A mirror: the laptop's own size if it has it, else the largest of its shape.
                    val source = mirrorSource()?.monitor?.takeIf { displays.mirrors(name) }
                    source?.let { s ->
                        modes.filter { it.width == s.pixelWidth && it.height == s.pixelHeight }.maxByOrNull { it.refreshMillihertz }
                            ?: modes.filter { it.width * s.pixelHeight == it.height * s.pixelWidth }.maxByOrNull { it.width.toLong() * it.height }
                    } ?: config.monitorSettings[name]?.let { wish ->
                        modes.filter { it.width == wish.width && it.height == wish.height }
                            .minByOrNull { mode -> wish.refreshHz?.let { Math.abs(mode.refreshMillihertz / 1000.0 - it) } ?: -mode.refreshMillihertz.toDouble() }
                    }
                }
                fun mirror(monitor: DrmOutput, source: Shown) {
                    val view = desktop.createView(source.monitor)
                    val mirror = dev.caulis.core.Mirror(backend.renderer)
                    mirrored[monitor] = Mirrored(view, mirror)
                    monitor.onFrame = { desktop.refresh(view, { draw -> monitor.present(mirror.wrap(view.monitor, draw)) }, force = monitor.needsPicture) }
                    monitor.onPresented = { nanos, refreshNanos, sequence, _ ->
                        desktop.presented(view, nanos, refreshNanos, sequence, WpPresentationFeedback.Kind.VSYNC)
                    }
                    println("${monitor.name} mirrors ${source.monitor.name}")
                }
                backend.onOutputAdded = add@{ monitor ->
                    val source = mirrorSource()
                    if (source != null && displays.mirrors(monitor.name)) {
                        mirror(monitor, source)
                        return@add
                    }
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
                    // The pointer on the display's cursor plane: it moves without new pictures.
                    if (config.hardwareCursor) view.hardwareCursor = monitor.hardwareCursor
                    shown[monitor] = Shown(output, place, view)
                    applyLink(monitor)
                    val stats = if (config.stats) FrameStats(monitor.name) else null
                    // A turned monitor gets its picture turned on the way (a program's buffer turned the same way is shown directly).
                    val rotator = dev.caulis.core.Rotator(backend.renderer, desktop)
                    val present = running.timed(stats) { draw -> monitor.present(rotator.wrap(place.transform, draw)) }
                    val presentDirect: ((dev.caulis.core.DmabufBuffer) -> Boolean)? =
                        if (config.directScanout) monitor::presentDirect else null
                    place.control = object : dev.caulis.core.MonitorControl {
                        override val modes get() = monitor.modes.map { dev.caulis.core.MonitorMode(it.width, it.height, it.refreshMillihertz, it.preferred) }
                        override val currentMode get() = monitor.mode?.let { dev.caulis.core.MonitorMode(it.width, it.height, it.refreshMillihertz, it.preferred) }
                        override val canTransform get() = true
                        override val gammaSize get() = monitor.gammaSize

                        override fun setMode(mode: dev.caulis.core.MonitorMode): Boolean {
                            val wanted = monitor.modes.firstOrNull { it.width == mode.width && it.height == mode.height && it.refreshMillihertz == mode.refreshMillihertz }
                                ?: monitor.modes.filter { it.width == mode.width && it.height == mode.height }.minByOrNull { Math.abs(it.refreshMillihertz - mode.refreshMillihertz) }
                                ?: return false
                            if (!monitor.changeMode(wanted)) return false
                            val turned = place.transform.sideways
                            desktop.resizeMonitor(place, if (turned) wanted.height else wanted.width, if (turned) wanted.width else wanted.height)
                            return true
                        }

                        override fun setPower(on: Boolean) = monitor.setPower(on)
                        override fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?) = monitor.setGamma(red, green, blue)
                    }
                    monitor.onFrame = { desktop.refresh(view, present, force = monitor.needsPicture, presentDirect = presentDirect) }
                    monitor.onPresented = { nanos, refreshNanos, sequence, zeroCopy ->
                        stats?.presented(nanos, refreshNanos)
                        // Exact: the kernel's own timestamp of the refresh that showed it.
                        // Exact times only when the kernel gives them on the monotonic clock.
                        val exact = if (backend.device.monotonicTimestamps) EXACT_PRESENTATION else WpPresentationFeedback.Kind.VSYNC
                        val flags = if (zeroCopy) exact or WpPresentationFeedback.Kind.ZERO_COPY else exact
                        desktop.presented(view, nanos, refreshNanos, sequence, flags)
                    }
                }
                backend.onOutputRemoved = { monitor ->
                    mirrored.remove(monitor)?.let { gone ->
                        desktop.removeView(gone.view)
                        gone.mirror.close()
                    }
                    shown.remove(monitor)?.let { gone ->
                        desktop.removeView(gone.view)
                        running.removeMonitor(gone.monitor)
                        gone.output.remove()
                    }
                }

                // Programs that arrange monitors (kanshi, wlr-randr) see the ones switched off too.
                running.outputManagement.heads = object : dev.caulis.core.Heads {
                    override fun heads() = backend.plugged().map { p ->
                        dev.caulis.core.Head(
                            p.name, listOfNotNull(p.edid?.make, p.edid?.model).joinToString(" ").ifEmpty { p.name },
                            p.edid?.make ?: "Unknown", p.edid?.model ?: p.name, p.edid?.serial.orEmpty(), p.widthMm, p.heightMm,
                            p.modes.map { dev.caulis.core.MonitorMode(it.width, it.height, it.refreshMillihertz, it.preferred) },
                            p.output?.let { shown[it]?.monitor },
                        )
                    }

                    override fun setEnabled(name: String, on: Boolean) = backend.setEnabled(name, on)
                }

                val input = backend.input
                input.onKey = running::key
                input.onPointerMotion = running::pointerMovedBy
                // Touch screens and devices that point at a place: on the monitor the configuration
                // names (touchscreenMonitor), else the laptop's own screen, else the first.
                val missingTouchMonitors = HashSet<String>()
                fun touchArea(): dev.caulis.geometry.Rect {
                    val monitors = desktop.monitors
                    running.config.touchscreenMonitor?.let { name ->
                        monitors.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.area }
                        if (missingTouchMonitors.add(name)) inputLog.warn("touchscreenMonitor is $name, which is not there (the laptop's screen or the first meanwhile)")
                    }
                    return (monitors.firstOrNull { running.config.isBuiltIn(it.name) } ?: monitors.first()).area
                }
                input.onPointerMotionAbsolute = { time, x, y ->
                    val area = touchArea()
                    desktop.pointerMoved(time, area.x + x * area.width, area.y + y * area.height)
                }
                input.onPointerButton = running::pointerButton
                input.onPointerAxis = running::pointerAxis
                input.onTouchDown = { time, id, x, y -> touchArea().let { a -> desktop.touchDown(time, id, a.x + x * a.width, a.y + y * a.height) } }
                input.onTouchMotion = { time, id, x, y -> touchArea().let { a -> desktop.touchMotion(time, id, a.x + x * a.width, a.y + y * a.height) } }
                input.onTouchUp = desktop::touchUp
                input.onTouchFrame = desktop::touchFrame
                input.onTouchCancel = desktop::touchCancel
                val devicesChanged = input.onDevicesChanged
                input.onDevicesChanged = {
                    devicesChanged()
                    desktop.touchScreen = input.hasTouchScreen
                }
                running.inputDevices = input
                input.onGesture = running::gesture
                // Input read on a thread of its own (inputThread), the cursor moved from there.
                input.earlyPointer = desktop.earlyPointer
                input.ownThread = running.config.inputThread

                backend.start()
                // With the reveal, the first picture (dark) goes to the monitors at once — not
                // only once onStart is done: the text console (and its cursor) is gone sooner.
                if (config.reveal.enabled) {
                    for (monitor in shown.keys) running.safely("the first frame of ${monitor.name}") { monitor.onFrame() }
                }
                if (shown.isEmpty()) println("No monitor is connected yet; waiting for one.")
                val quitting = running.config.keys.quit?.let { "$it quits." } ?: "No key quits (keys.quit is off)."
                println("Programs inside it use WAYLAND_DISPLAY=${running.socket}. $quitting")
                Watchdog(loop).use { running.runLoop() }
            }
        }
    }
}

// The time comes from the display hardware at the refresh (vsync) itself.
private const val EXACT_PRESENTATION = WpPresentationFeedback.Kind.VSYNC or
    WpPresentationFeedback.Kind.HW_CLOCK or WpPresentationFeedback.Kind.HW_COMPLETION

private val inputLog = dev.caulis.log.Logger("input")

private const val DEFAULT_WIDTH = 1920
private const val DEFAULT_HEIGHT = 1080
