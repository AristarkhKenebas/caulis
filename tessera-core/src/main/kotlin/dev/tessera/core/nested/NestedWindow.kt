package dev.tessera.core.nested

import dev.tessera.event.EventLoop
import dev.tessera.ffi.SharedMemory
import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.wayland.WlBuffer
import dev.tessera.protocol.client.wayland.WlCallback
import dev.tessera.protocol.client.wayland.WlCompositor
import dev.tessera.protocol.client.wayland.WlKeyboard
import dev.tessera.protocol.client.wayland.WlPointer
import dev.tessera.protocol.client.wayland.WlRegistry
import dev.tessera.protocol.client.wayland.WlSeat
import dev.tessera.protocol.client.wayland.WlShm
import dev.tessera.protocol.client.wayland.WlShmPool
import dev.tessera.protocol.client.wayland.WlSurface
import dev.tessera.protocol.client.xdgshell.XdgSurface
import dev.tessera.protocol.client.xdgshell.XdgToplevel
import dev.tessera.protocol.client.xdgshell.XdgWmBase
import java.lang.foreign.MemorySegment

/**
 * Tessera's window inside an existing Wayland session (the "nested" backend).
 *
 * Tessera draws each frame into memory; this window shows it in the outer session.
 * Frames are paced by the outer compositor: [onFrame] is called when it is a good moment
 * to draw the next one.
 */
class NestedWindow(loop: EventLoop, title: String = "Tessera") : AutoCloseable {

    val connection: Connection = Connection.connect()

    // Globals of the outer compositor.
    private val compositor: WlCompositor
    private val shm: WlShm
    private val wmBase: XdgWmBase

    /** The outer session's seat (keyboard and mouse), if it has one. */
    val seat: WlSeat?

    private val surface: WlSurface
    private val xdgSurface: XdgSurface
    private val toplevel: XdgToplevel

    /** Size of the window in pixels; changes when the user resizes it. */
    var width = DEFAULT_WIDTH
        private set
    var height = DEFAULT_HEIGHT
        private set

    /** Called when the window got a new size (before the next [onFrame]). */
    var onResize: (width: Int, height: Int) -> Unit = { _, _ -> }

    /** Called when the outer compositor is ready for a new frame: draw it and call [present]. */
    var onFrame: () -> Unit = {}

    /** Called when the user closes the window. */
    var onClose: () -> Unit = {}

    /** Input from the outer session, in window pixels. Times are in milliseconds. */
    var onPointerMotion: (timeMillis: Int, x: Double, y: Double) -> Unit = { _, _, _ -> }
    var onPointerButton: (timeMillis: Int, button: Int, pressed: Boolean) -> Unit = { _, _, _ -> }
    var onPointerAxis: (timeMillis: Int, vertical: Boolean, value: Double, value120: Int) -> Unit = { _, _, _, _ -> }
    var onKey: (timeMillis: Int, evdevCode: Int, pressed: Boolean) -> Unit = { _, _, _ -> }

    /** The outer session took the keyboard away (all keys count as released). */
    var onKeyboardLeave: () -> Unit = {}

    private var configured = false
    private var frameRequested = false
    private val buffers = ArrayList<Buffer>()

    init {
        var compositorName = 0
        var shmName = 0
        var wmBaseName = 0
        var seatName = 0
        var seatVersion = 0
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                when (`interface`) {
                    "wl_compositor" -> compositorName = name
                    "wl_shm" -> shmName = name
                    "xdg_wm_base" -> wmBaseName = name
                    "wl_seat" -> if (seatName == 0) {
                        seatName = name
                        seatVersion = versionArg
                    }
                }
            }
        }
        connection.roundtrip()
        check(compositorName != 0 && shmName != 0 && wmBaseName != 0) {
            "The outer compositor lacks wl_compositor, wl_shm or xdg_wm_base"
        }

        compositor = registry.bind(compositorName, WlCompositor, 4)
        shm = registry.bind(shmName, WlShm, 1)
        wmBase = registry.bind(wmBaseName, XdgWmBase, 1)
        // Version 8 adds high-resolution scroll steps (axis_value120).
        seat = if (seatName != 0) registry.bind(seatName, WlSeat, minOf(seatVersion, 8)) else null
        seat?.let(::listenToInput)

        // The outer compositor checks we are alive; answer or it marks us "not responding".
        wmBase.events = object : XdgWmBase.Events {
            override fun ping(proxy: XdgWmBase, serial: Int) = proxy.pong(serial)
        }

        surface = compositor.createSurface()
        xdgSurface = wmBase.getXdgSurface(surface)
        toplevel = xdgSurface.getToplevel()
        toplevel.setTitle(title)
        toplevel.setAppId("dev.tessera.Nested")

        toplevel.events = object : XdgToplevel.Events {
            override fun configure(proxy: XdgToplevel, width: Int, height: Int, states: ByteArray) {
                // 0 means "choose yourself".
                if (width > 0 && height > 0 && (width != this@NestedWindow.width || height != this@NestedWindow.height)) {
                    this@NestedWindow.width = width
                    this@NestedWindow.height = height
                    onResize(width, height)
                }
            }

            override fun close(proxy: XdgToplevel) = onClose()
        }
        xdgSurface.events = object : XdgSurface.Events {
            override fun configure(proxy: XdgSurface, serial: Int) {
                proxy.ackConfigure(serial)
                if (!configured) {
                    configured = true
                    onFrame() // the first frame
                }
            }
        }
        // The first commit without a buffer asks the outer compositor for a size.
        surface.commit()
        connection.attach(loop)
    }

    private var pendingValue120 = intArrayOf(0, 0)

    // Asks for the outer keyboard and pointer when the seat has them.
    private fun listenToInput(seat: WlSeat) {
        var keyboard: WlKeyboard? = null
        var pointer: WlPointer? = null
        seat.events = object : WlSeat.Events {
            override fun capabilities(proxy: WlSeat, capabilities: Int) {
                if (capabilities and WlSeat.Capability.KEYBOARD != 0 && keyboard == null) {
                    keyboard = proxy.getKeyboard().also { it.events = keyboardEvents }
                }
                if (capabilities and WlSeat.Capability.POINTER != 0 && pointer == null) {
                    pointer = proxy.getPointer().also { it.events = pointerEvents }
                }
            }
        }
    }

    private val keyboardEvents = object : WlKeyboard.Events {
        // We use our own keymap; the outer one is not needed.
        override fun keymap(proxy: WlKeyboard, format: Int, fd: dev.tessera.ffi.Fd, size: Int) = fd.close()

        override fun leave(proxy: WlKeyboard, serial: Int, surface: WlSurface) = onKeyboardLeave()

        override fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) =
            onKey(time, key, state == WlKeyboard.KeyState.PRESSED)
    }

    private val pointerEvents = object : WlPointer.Events {
        override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
            // Hide the outer cursor over our window: Tessera draws its own.
            proxy.setCursor(serial, null, 0, 0)
            onPointerMotion(0, surfaceX, surfaceY)
        }

        override fun motion(proxy: WlPointer, time: Int, surfaceX: Double, surfaceY: Double) =
            onPointerMotion(time, surfaceX, surfaceY)

        override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) =
            onPointerButton(time, button, state == WlPointer.ButtonState.PRESSED)

        override fun axisValue120(proxy: WlPointer, axis: Int, value120: Int) {
            pendingValue120[axis.coerceIn(0, 1)] += value120
        }

        override fun axis(proxy: WlPointer, time: Int, axis: Int, value: Double) {
            val index = axis.coerceIn(0, 1)
            onPointerAxis(time, axis == WlPointer.Axis.VERTICAL_SCROLL, value, pendingValue120[index])
            pendingValue120[index] = 0
        }
    }

    /**
     * Shows a new frame. [draw] fills the pixels (32-bit XRGB, [width]x[height],
     * `stride` bytes per row). Afterwards [onFrame] is called again when the outer
     * compositor wants the next frame.
     */
    fun present(draw: (pixels: MemorySegment, stride: Int) -> Unit) {
        val buffer = freeBuffer()
        buffer.memory.write { pixels -> draw(pixels, buffer.stride) }
        surface.attach(buffer.wlBuffer, 0, 0)
        surface.damageBuffer(0, 0, width, height)
        buffer.busy = true
        if (!frameRequested) {
            frameRequested = true
            surface.frame().events = object : WlCallback.Events {
                override fun done(proxy: WlCallback, callbackData: Int) {
                    frameRequested = false
                    onFrame()
                }
            }
        }
        surface.commit()
    }

    // A buffer the outer compositor is not reading right now, of the current size.
    private fun freeBuffer(): Buffer {
        buffers.removeAll { buffer ->
            val outdated = buffer.width != width || buffer.height != height
            if (outdated && !buffer.busy) buffer.close()
            outdated && !buffer.busy
        }
        buffers.firstOrNull { !it.busy && it.width == width && it.height == height }?.let { return it }
        return Buffer(width, height).also { buffers += it }
    }

    /** One shared-memory picture the outer compositor can show. */
    private inner class Buffer(val width: Int, val height: Int) : AutoCloseable {
        val stride = width * 4
        val memory = SharedMemory.create("tessera-nested", stride.toLong() * height)
        private val pool: WlShmPool = shm.createPool(memory.fd, (stride.toLong() * height).toInt())
        val wlBuffer: WlBuffer = pool.createBuffer(0, width, height, stride, WlShm.Format.XRGB8888)
        var busy = false

        init {
            // The outer compositor is done reading; the buffer may be reused.
            wlBuffer.events = object : WlBuffer.Events {
                override fun release(proxy: WlBuffer) {
                    busy = false
                }
            }
        }

        override fun close() {
            wlBuffer.destroy()
            pool.destroy()
            memory.close()
        }
    }

    override fun close() {
        buffers.forEach { it.close() }
        toplevel.destroy()
        xdgSurface.destroy()
        surface.destroy()
        connection.flush()
        connection.close()
    }

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 800
    }
}
