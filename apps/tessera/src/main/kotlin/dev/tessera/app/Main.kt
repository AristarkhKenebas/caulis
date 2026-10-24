package dev.tessera.app

import dev.tessera.core.Compositor
import dev.tessera.core.DataDeviceManager
import dev.tessera.core.Desktop
import dev.tessera.core.Output
import dev.tessera.core.Shm
import dev.tessera.core.XdgShell
import dev.tessera.core.input.Keymap
import dev.tessera.core.input.Seat
import dev.tessera.core.nested.NestedWindow
import dev.tessera.event.EventLoop
import dev.tessera.protocol.Display
import dev.tessera.render.GpuTarget
import dev.tessera.render.gles.GlesRenderer

/**
 * Runs Tessera as a window inside the current Wayland session.
 * Programs started with the printed WAYLAND_DISPLAY open inside it.
 */
fun main() {
    EventLoop().use { loop ->
        GlesRenderer.create().use { renderer ->
            Display(loop).use { display ->
                NestedWindow(loop).use { window ->
                    Keymap.fromNames().use { keymap ->
                        Seat(display, keymap).use { seat ->
                            run(loop, display, window, renderer, seat)
                        }
                    }
                }
            }
        }
    }
}

private fun run(loop: EventLoop, display: Display, window: NestedWindow, renderer: GlesRenderer, seat: Seat) {
    val output = Output(display, "NESTED-1", "Tessera (nested)", window.width, window.height, 60_000)
    Compositor(display, renderer)
    Shm(display)
    DataDeviceManager(display, seat)
    val shell = XdgShell(display)
    val desktop = Desktop(renderer, seat, window.width, window.height)
    shell.listener = desktop

    val socket = display.addSocket()
    println("Tessera is running. Start programs inside it with: WAYLAND_DISPLAY=$socket <program>")
    println("Renderer: ${renderer.description}")

    // Input from the outer session goes to the window manager.
    window.onPointerMotion = desktop::pointerMoved
    window.onPointerButton = desktop::pointerButton
    window.onPointerAxis = desktop::pointerAxis
    window.onKey = desktop::key
    window.onKeyboardLeave = { seat.releaseAllKeys(now()) }
    window.onClose = { loop.stop() }

    window.onResize = { width, height ->
        output.resize(width, height)
        desktop.width = width
        desktop.height = height
    }

    // Each frame: draw everything, show it, then tell clients their frame is on screen.
    var target: GpuTarget? = null
    window.onFrame = {
        val current = target?.takeIf { it.width == window.width && it.height == window.height }
            ?: renderer.createTarget(window.width, window.height).also {
                target?.close()
                target = it
            }
        desktop.render(current)
        window.present { pixels, stride -> current.readPixels(pixels, stride) }
        desktop.frameShown(now())
    }

    loop.run()
    target?.close()
}

private val start = System.nanoTime()

/** Milliseconds since Tessera started (Wayland event timestamps). */
private fun now(): Int = ((System.nanoTime() - start) / 1_000_000).toInt()
