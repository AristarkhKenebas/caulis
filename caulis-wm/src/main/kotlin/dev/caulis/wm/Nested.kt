package dev.caulis.wm

import dev.caulis.core.FrameStats
import dev.caulis.core.Output
import dev.caulis.core.nested.NestedWindow
import dev.caulis.event.EventLoop
import dev.caulis.render.gles.GlesRenderer

/** The compositor as a window inside the current Wayland session. */
internal fun runNested(config: Caulis) {
    EventLoop().use { loop ->
        GlesRenderer.create().use { renderer ->
            NestedWindow(loop, renderer, title = config.title, appId = config.appId).use { window ->
                Running(config, loop, renderer, window.width, window.height, switchConsole = null).use { running ->
                    val desktop = running.desktop
                    val output = Output(running.display, "NESTED-1", "${config.title} (nested)", window.width, window.height, 60_000)
                    println("${config.title} is running. Start programs inside it with: WAYLAND_DISPLAY=${running.socket} <program>")
                    println("Renderer: ${renderer.description}")
                    println(if (window.sharesGpuBuffers) "Frames go to the outer session as GPU buffers" else "Frames are copied to the outer session")

                    // Input from the outer session goes to the window manager.
                    window.onPointerMotion = desktop::pointerMoved
                    window.onPointerButton = running::pointerButton
                    window.onPointerAxis = desktop::pointerAxis
                    window.onKey = running::key
                    window.onKeyboardLeave = {
                        running.keys.releaseAll()
                        running.seat.releaseAllKeys(now())
                    }
                    window.onClose = running::quit

                    window.onResize = { width, height ->
                        output.resize(width, height)
                        desktop.width = width
                        desktop.height = height
                    }

                    // Each refresh of the outer session: draw what changed (if anything),
                    // then tell programs their last frame is on screen.
                    val view = desktop.createView()
                    val stats = if (config.stats) FrameStats("NESTED-1") else null
                    val present = running.timed(stats, window::present)
                    window.onFrame = { desktop.refresh(view, present) }
                    window.onPresented = { nanos ->
                        stats?.presented(nanos, refreshNanos = 0)
                        desktop.presented(view, nanos, refreshNanos = 0, sequence = 0, flags = 0)
                    }
                    desktop.onChange = window::requestFrame

                    running.runLoop()
                    stats?.close()
                }
            }
        }
    }
}
