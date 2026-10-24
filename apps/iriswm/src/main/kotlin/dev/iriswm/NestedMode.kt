package dev.iriswm

import dev.caulis.core.FrameStats
import dev.caulis.core.Output
import dev.caulis.core.nested.NestedWindow
import dev.caulis.event.EventLoop
import dev.caulis.render.gles.GlesRenderer

/** irisWM as a window inside the current Wayland session. */
fun runNested(options: Options) {
    EventLoop().use { loop ->
        GlesRenderer.create().use { renderer ->
            NestedWindow(loop, renderer, title = "irisWM", appId = "dev.iriswm.Nested").use { window ->
                IrisCompositor(loop, renderer, window.width, window.height).use { iris ->
                    val desktop = iris.desktop
                    val output = Output(iris.display, "NESTED-1", "irisWM (nested)", window.width, window.height, 60_000)
                    println("irisWM is running. Start programs inside it with: WAYLAND_DISPLAY=${iris.socket} <program>")
                    println("Renderer: ${renderer.description}")
                    println(if (window.sharesGpuBuffers) "Frames go to the outer session as GPU buffers" else "Frames are copied to the outer session")

                    val bindings = Bindings(desktop, quit = loop::stop, openTerminal = { iris.launch(options.terminal) }, switchConsole = null)

                    // Input from the outer session goes to the window manager.
                    window.onPointerMotion = desktop::pointerMoved
                    window.onPointerButton = bindings::pointerButton
                    window.onPointerAxis = desktop::pointerAxis
                    window.onKey = bindings::key
                    window.onKeyboardLeave = {
                        bindings.keys.releaseAll()
                        iris.seat.releaseAllKeys(now())
                    }
                    window.onClose = { loop.stop() }

                    window.onResize = { width, height ->
                        output.resize(width, height)
                        desktop.width = width
                        desktop.height = height
                    }

                    // Each refresh of the outer session: draw what changed (if anything),
                    // then tell programs their last frame is on screen.
                    val view = desktop.createView()
                    val stats = if (options.stats) FrameStats("NESTED-1") else null
                    val present = timed(stats, window::present)
                    window.onFrame = { desktop.refresh(view, present) }
                    window.onPresented = { nanos ->
                        stats?.presented(nanos, refreshNanos = 0)
                        desktop.presented(view, nanos, refreshNanos = 0, sequence = 0, flags = 0)
                    }
                    desktop.onChange = window::requestFrame

                    options.exitAfterSeconds?.let { seconds -> loop.after(seconds * 1000) { loop.stop() } }
                    options.run.forEach(iris::launch)
                    loop.run()
                }
            }
        }
    }
}
