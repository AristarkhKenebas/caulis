package dev.caulis.core

import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.linuxdmabufv1.ZwpLinuxDmabufV1
import dev.caulis.protocol.client.presentationtime.WpPresentation
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerShellV1
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlSeat
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.xdgshell.XdgSurface
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.protocol.client.xdgshell.XdgWmBase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A small Wayland client for tests: opens windows, draws plain pictures, waits for answers. */
class TestClient(val connection: Connection) : AutoCloseable {

    private val names = HashMap<String, Int>()
    private val registry = connection.display.getRegistry().also { registry ->
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                names[`interface`] = name
            }
        }
        connection.roundtrip()
    }

    val compositor = registry.bind(names.getValue("wl_compositor"), WlCompositor, 6)
    val seat = registry.bind(names.getValue("wl_seat"), WlSeat, 1)
    private val shm = registry.bind(names.getValue("wl_shm"), WlShm, 1)
    val wmBase = registry.bind(names.getValue("xdg_wm_base"), XdgWmBase, 6)
    val presentation = registry.bind(names.getValue("wp_presentation"), WpPresentation, 1)
    val dmabuf: ZwpLinuxDmabufV1? = names["zwp_linux_dmabuf_v1"]?.let { registry.bind(it, ZwpLinuxDmabufV1, 4) }
    val layerShell: ZwlrLayerShellV1? = names["zwlr_layer_shell_v1"]?.let { registry.bind(it, ZwlrLayerShellV1, 4) }
    private val memory = SharedMemory.create("pictures", MAX_SIZE.toLong() * MAX_SIZE * 4)

    /** A window and what the compositor last asked of it. */
    inner class TestWindow(val surface: WlSurface, val toplevel: XdgToplevel) {
        var width = 0
        var height = 0
        var states: Set<Int> = emptySet()

        /** Draws a picture of the size the compositor asked for (or [defaultWidth] x [defaultHeight]). */
        fun draw(defaultWidth: Int = 64, defaultHeight: Int = 64) = draw(surface, width.takeIf { it > 0 } ?: defaultWidth, height.takeIf { it > 0 } ?: defaultHeight)
    }

    /** Opens a window and waits for the first configure (the picture comes with [TestWindow.draw]). */
    fun window(beforeFirstCommit: (XdgToplevel) -> Unit = {}): TestWindow {
        val surface = compositor.createSurface()
        val xdgSurface = wmBase.getXdgSurface(surface)
        val toplevel = xdgSurface.getToplevel()
        val window = TestWindow(surface, toplevel)
        toplevel.events = object : XdgToplevel.Events {
            override fun configure(proxy: XdgToplevel, width: Int, height: Int, states: ByteArray) {
                window.width = width
                window.height = height
                val values = ByteBuffer.wrap(states).order(ByteOrder.nativeOrder())
                window.states = buildSet { while (values.remaining() >= 4) add(values.int) }
            }
        }
        xdgSurface.events = object : XdgSurface.Events {
            override fun configure(proxy: XdgSurface, serial: Int) = proxy.ackConfigure(serial)
        }
        beforeFirstCommit(toplevel)
        surface.commit()
        connection.roundtrip()
        return window
    }

    /** Attaches a plain [width] x [height] picture and commits. */
    fun draw(surface: WlSurface, width: Int, height: Int, commit: Boolean = true) {
        require(width <= MAX_SIZE && height <= MAX_SIZE)
        val pool = shm.createPool(memory.fd, MAX_SIZE * MAX_SIZE * 4)
        surface.attach(pool.createBuffer(0, width, height, width * 4, WlShm.Format.XRGB8888), 0, 0)
        surface.damageBuffer(0, 0, width, height)
        pool.destroy()
        if (commit) surface.commit()
    }

    /** Exchanges messages until [condition] holds (at most 5 seconds). */
    fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            connection.roundtrip()
            Thread.sleep(10)
        }
    }

    override fun close() {
        memory.close()
        connection.close()
    }

    companion object {
        const val MAX_SIZE = 2048
    }
}
