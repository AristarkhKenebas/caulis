package dev.iriswm

import dev.caulis.core.Compositor
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.FrameStats
import dev.caulis.core.Desktop
import dev.caulis.core.LinuxDmabuf
import dev.caulis.core.Presentation
import dev.caulis.core.Shm
import dev.caulis.core.XdgShell
import dev.caulis.core.input.Keymap
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/**
 * The part of irisWM that is the same in every mode: the Wayland socket, the features
 * offered to programs, the window manager ([desktop]) and starting programs.
 */
class IrisCompositor(
    val loop: EventLoop,
    val renderer: Renderer,
    width: Int,
    height: Int,
) : AutoCloseable {

    val display = Display(loop)
    private val keymap = Keymap.fromNames()
    val seat = Seat(display, keymap)
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    init {
        Shm(display)
        if (renderer.renderNode != null) LinuxDmabuf(display, renderer)
        DataDeviceManager(display, seat)
        Presentation(display)
        XdgShell(display).listener = desktop
        socket = display.addSocket()
    }

    /** Starts [command] (run by /bin/sh) as a program inside irisWM. */
    fun launch(command: String) {
        try {
            val builder = ProcessBuilder("/bin/sh", "-c", command)
            builder.environment().apply {
                put("WAYLAND_DISPLAY", socket)
                remove("DISPLAY") // no X11 server inside irisWM (yet)
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.start()
        } catch (e: Exception) {
            System.err.println("Could not start \"$command\": ${e.message}")
        }
    }

    override fun close() {
        seat.close()
        keymap.close()
        display.close()
    }
}

/**
 * Wraps a backend's `present` so drawing is timed by [stats] (if measuring is on).
 */
fun timed(
    stats: FrameStats?,
    present: ((GpuTarget, Int) -> Unit) -> Unit,
): ((GpuTarget, Int) -> Unit) -> Unit {
    if (stats == null) return present
    return { draw ->
        present { target, age ->
            stats.drawStarted()
            draw(target, age)
            stats.drawFinished()
        }
    }
}

/** Milliseconds since the system started (the clock Wayland and libinput timestamps use). */
fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
