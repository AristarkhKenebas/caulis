package dev.caulis.wm

import dev.caulis.core.Output
import dev.caulis.event.EventLoop
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.render.gles.GlesRenderer

/**
 * The compositor with monitors that are nowhere on screen (headless): it draws them on the
 * GPU off screen, at up to [Caulis.headlessMonitors] monitors of their [Caulis.monitor] mode
 * (else 1920x1080). Programs connect as usual; screenshots and screen sharing see the
 * monitors. For trying a compositor while sitting in another one, tests, and virtual
 * monitors to share.
 */
internal fun runHeadless(config: Caulis) {
    EventLoop().use { loop ->
        GlesRenderer.create().use { renderer ->
            val count = config.headlessMonitors.coerceIn(1, 8)
            val sizes = (1..count).map { n ->
                val settings = config.monitorSettings["HEADLESS-$n"]
                (settings?.width ?: 1920) to (settings?.height ?: 1080)
            }
            Running(config, loop, renderer, sizes[0].first, sizes[0].second, switchConsole = null).use { running ->
                val desktop = running.desktop
                println("${config.title} is running headless on $count monitor${if (count > 1) "s" else ""}. Start programs inside it with: WAYLAND_DISPLAY=${running.socket} <program>")
                val ticks = ArrayList<() -> Unit>()
                sizes.forEachIndexed { index, (width, height) ->
                    val name = "HEADLESS-${index + 1}"
                    val settings = config.monitorSettings[name]
                    val hz = settings?.refreshHz ?: 60.0
                    val refreshNanos = (1_000_000_000 / hz).toLong()
                    val output = Output(running.display, name, "${config.title} (headless)", width, height, (hz * 1000).toInt(), scale = settings?.scale ?: config.scale)
                    val monitor = running.addMonitor(name, output, width, height)
                    val view = desktop.createView(monitor)
                    val target = renderer.createTarget(width, height)
                    var sequence = 0L
                    var waiting = false
                    // A frame when something changed, at most one per refresh (as a monitor would).
                    fun tick() {
                        if (waiting) return
                        waiting = true
                        loop.after(refreshNanos / 1_000_000) {
                            waiting = false
                            if (desktop.refresh(view, { draw -> draw(target, 1) }, presentDirect = { false })) {
                                desktop.presented(view, System.nanoTime(), refreshNanos, ++sequence, WpPresentationFeedback.Kind.VSYNC)
                            }
                        }
                    }
                    ticks += ::tick
                }
                desktop.onChange = { ticks.forEach { it() } }
                ticks.forEach { it() }
                running.runLoop()
            }
        }
    }
}
