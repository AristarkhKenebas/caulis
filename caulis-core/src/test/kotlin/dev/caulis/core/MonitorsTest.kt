package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleManagerV1
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleV1
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Several monitors: one desktop, each monitor with its own workspace, panels and scale. */
class MonitorsTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `monitors side by side, each showing a workspace of its own`() = withServer { server, client ->
        val (left, right) = server.onServer { d -> d.monitors.map { it.area to it.workspace.number } }
        assertEquals(Rect(0, 0, width, height) to 1, left)
        assertEquals(Rect(width, 0, width, height) to 2, right)
        // A window opens on the monitor with the focus ...
        val first = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        // ... and after the focus went to the other one, there.
        server.onServer { d -> d.focusMonitor(d.monitors[1]) }
        val second = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.monitors[1].workspace.windows.size == 1 } }
        server.onServer { d -> d.animationMillis = 0 }
        client.waitFor { server.onServer { d -> d.monitors.map { m -> m.workspace.windows.single().x } } .let { (a, b) -> a < width && b >= width } }
        // Each monitor's picture shows its own window, nothing of the other.
        first.draw()
        second.draw()
        val leftPicture = server.screenshot(0)
        val rightPicture = server.screenshot(1)
        val (a, b) = server.onServer { d -> d.monitors.map { m -> m.toPixels(d.screenRect(m.workspace.windows.single())!!) } }
        assertEquals(BLACK, leftPicture[(a.y + a.height / 2) * width + a.x + a.width / 2], "the left window on the left monitor")
        assertEquals(BLACK, rightPicture[(b.y + b.height / 2) * width + b.x + b.width / 2], "the right window on the right monitor, at its own corner")
    }

    @Test
    fun `Super+N shows a workspace on the monitor with the focus, or goes to where it is`() = withServer { server, _ ->
        server.onServer { d ->
            d.switchTo(3)
            assertEquals(listOf(3, 2), d.monitors.map { it.workspace.number })
            d.switchTo(2) // on the right monitor: the focus goes there
            assertEquals("TEST-2", d.focusedMonitor.name)
            assertEquals(listOf(3, 2), d.monitors.map { it.workspace.number })
        }
    }

    @Test
    fun `spanning workspaces switch every monitor at once`() = withServer(WorkspaceMode.SPANNING) { server, _ ->
        server.onServer { d ->
            assertEquals(listOf(1, 1), d.monitors.map { it.workspace.number }, "one workspace on both")
            assertNotEquals(d.monitors[0].workspace, d.monitors[1].workspace, "each monitor has its own part of it")
            d.switchTo(4)
            assertEquals(listOf(4, 4), d.monitors.map { it.workspace.number })
        }
    }

    @Test
    fun `the pointer moves across, and stops at outer edges`() = withServer { server, _ ->
        server.onServer { d ->
            d.pointerMoved(0, width + 100.0, 50.0)
            assertEquals(width + 100.0, d.pointerX, "on the right monitor")
            d.pointerMoved(0, 3.0 * width, -40.0)
            assertEquals(2.0 * width - 1 to 0.0, d.pointerX to d.pointerY, "held at the corner")
        }
    }

    @Test
    fun `fullscreen covers its own monitor only`() = withServer { server, client ->
        server.onServer { d -> d.focusMonitor(d.monitors[1]) }
        val window = client.window()
        window.toplevel.setFullscreen(null)
        window.draw()
        client.waitFor { window.width == width && window.height == height }
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.let { it.x to it.y } } == (width to 0) }
    }

    @Test
    fun `a window learns the monitors it is on and their scale`() = withServer { server, client ->
        server.onServer { d -> d.setScale(d.monitors[1], 2.0) }
        val entered = java.util.concurrent.CopyOnWriteArrayList<WlOutput>()
        var bufferScale = 0
        var fraction = 0
        val window = client.window()
        window.surface.events = object : WlSurface.Events {
            override fun enter(proxy: WlSurface, output: WlOutput) { entered += output }
            override fun leave(proxy: WlSurface, output: WlOutput) { entered -= output }
            override fun preferredBufferScale(proxy: WlSurface, factor: Int) { bufferScale = factor }
        }
        client.bind("wp_fractional_scale_manager_v1", WpFractionalScaleManagerV1, 1).getFractionalScale(window.surface).events =
            object : WpFractionalScaleV1.Events {
                override fun preferredScale(proxy: WpFractionalScaleV1, scale: Int) { fraction = scale }
            }
        window.draw()
        // (it entered the first monitor while it was made, before we listened)
        client.waitFor { fraction == 120 }
        // Moved to the other monitor: it leaves the first, enters the second, draws at its scale.
        server.onServer { d -> d.moveToMonitor(d.windows.single(), d.monitors[1]) }
        window.draw()
        try {
            client.waitFor { client.outputs[1] in entered && client.outputs[0] !in entered && fraction == 240 && bufferScale == 2 }
        } catch (e: IllegalStateException) {
            error("then: entered ${entered.map { client.outputs.indexOf(it) }}, fraction $fraction, buffer scale $bufferScale")
        }
    }

    @Test
    fun `panels belong to their monitor`() = withServer { server, client ->
        server.onServer { it.layout = Columns(Gaps(0, 0)) }
        val surface = client.compositor.createSurface()
        val bar = client.layerShell!!.getLayerSurface(surface, client.outputs[1], 2, "bar")
        var size = 0 to 0
        bar.events = object : ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) {
                size = width to height
                proxy.ackConfigure(serial)
            }
        }
        bar.setSize(0, 30)
        bar.setAnchor(TOP or LEFT or RIGHT)
        bar.setExclusiveZone(30)
        surface.commit()
        client.waitFor { size.first > 0 }
        assertEquals(width to 30, size, "as wide as its own monitor")
        client.draw(surface, size.first, size.second)
        client.waitFor { server.onServer { d -> d.monitors[1].usableArea } == Rect(width, 30, width, height - 30) }
        assertEquals(Rect(0, 0, width, height), server.onServer { d -> d.monitors[0].usableArea }, "the other monitor keeps all its room")
    }

    @Test
    fun `a monitor that goes away leaves its workspace to come back on another`() = withServer { server, client ->
        server.onServer { d -> d.focusMonitor(d.monitors[1]) }
        client.window().draw()
        client.waitFor { server.onServer { d -> d.monitors[1].workspace.windows.size == 1 } }
        server.onServer { d ->
            d.removeMonitor(d.monitors[1])
            assertEquals(1, d.monitors.size)
            assertEquals("TEST-1", d.focusedMonitor.name)
            d.switchTo(2)
            assertEquals(1, d.windows.size, "workspace 2 with its window, now on the remaining monitor")
            assertTrue(d.monitors[0].area.contains(d.windows.single().let { Rect(it.x, it.y, 1, 1) }), "placed on it")
        }
    }

    @Test
    fun `a placement opens a window on its monitor, maximized from the first frame`() = withServer { server, client ->
        server.onServer { d -> d.placementFor = { WindowPlacement(monitor = "TEST-2", maximized = true) } }
        val window = client.window()
        assertEquals(width to height, window.width to window.height, "the whole monitor in the very first configure")
        assertTrue(dev.caulis.protocol.client.xdgshell.XdgToplevel.State.MAXIMIZED in window.states)
        window.draw()
        client.waitFor { server.onServer { d -> d.allWindows.singleOrNull()?.mapped } == true }
        server.onServer { d ->
            assertEquals("TEST-2", d.monitorOf(d.allWindows.single()).name)
            assertEquals(width, d.allWindows.single().x, "at the second monitor's corner")
        }
    }

    @Test
    fun `the mouse between monitors of different sizes, as Windows or edge to edge`() = withServer { server, _ ->
        server.onServer { d ->
            d.resizeMonitor(d.monitors[1], width, height / 2) // the right one is half as high, tops level
            val small = d.monitors[1].area
            assertEquals(Rect(width, 0, width, height / 2), small)

            // As Windows: low on the left monitor, the right one is not there — it stops.
            d.pointerMoved(0, width - 5.0, height - 20.0)
            d.pointerMovedBy(0, 50.0, 0.0)
            assertEquals(width - 1.0 to height - 20.0, d.pointerX to d.pointerY, "stopped at the edge")
            // Where they touch, it goes across.
            d.pointerMoved(0, width - 5.0, 20.0)
            d.pointerMovedBy(0, 50.0, 0.0)
            assertEquals("TEST-2", d.monitorAt(d.pointerX, d.pointerY).name)

            // Edge to edge: the bottom of the left leads to the bottom of the right.
            d.monitorEdges = MonitorEdges.PROPORTIONAL
            d.pointerMoved(0, width - 5.0, height - 1.0)
            d.pointerMovedBy(0, 50.0, 0.0)
            assertEquals("TEST-2", d.monitorAt(d.pointerX, d.pointerY).name)
            assertEquals(small.bottom - 1.0, d.pointerY, 1.0, "its bottom")
            // And back: the middle of the right leads to the middle of the left.
            d.pointerMoved(0, width + 5.0, small.height / 2.0)
            d.pointerMovedBy(0, -50.0, 0.0)
            assertEquals("TEST-1", d.monitorAt(d.pointerX, d.pointerY).name)
            assertEquals(height / 2.0, d.pointerY, 2.0, "the middle")
        }
    }

    @Test
    fun `a wallpaper over both monitors goes on from one to the other`() = withServer { server, _ ->
        server.onServer { d ->
            d.backgroundChangeMillis = 0
            d.background = Background.shader(WallpaperShader("vec4 wallpaper(vec2 at) { return vec4(desktopAt(at).x, 0.0, 0.0, 1.0); }"))
        }
        val left = server.screenshot(0)
        val right = server.screenshot(1)
        fun red(p: Int) = p shr 16 and 0xff
        val leftEnd = red(left[100 * width + width - 1])
        val rightStart = red(right[100 * width])
        assertTrue(red(left[100 * width]) < 10, "black at the far left")
        assertTrue(Math.abs(leftEnd - 128) < 6 && Math.abs(rightStart - 128) < 6, "half red where they meet: $leftEnd, $rightStart")
        assertTrue(red(right[100 * width + width - 1]) > 245, "red at the far right")

        // A picture over both: red on the left half of it, blue on the right.
        val file = Files.createTempFile("caulis-span", ".png")
        try {
            val image = java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 200) for (y in 0 until 100) image.setRGB(x, y, if (x < 100) 0xFF0000 else 0x0000FF)
            javax.imageio.ImageIO.write(image, "png", file.toFile())
            server.onServer { d -> d.background = Background.image(file, Background.Fit.STRETCH, span = true) }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (server.screenshot(0)[200 * width + 50] != RED) {
                check(System.nanoTime() < deadline) { "the picture did not come" }
                Thread.sleep(50)
            }
            assertEquals(RED, server.screenshot(0)[200 * width + width - 50], "the left monitor: the red half")
            assertEquals(BLUE, server.screenshot(1)[200 * width + 50], "the right monitor: the blue half")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun withServer(mode: WorkspaceMode = WorkspaceMode.PER_MONITOR, test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer(monitorCount = 2, workspaceMode = mode).use { server ->
            TestClient(Connection.connect(server.socketPath)).use { test(server, it) }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val TOP = 1
        const val LEFT = 4
        const val RIGHT = 8
    }
}
