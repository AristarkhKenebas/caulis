package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleManagerV1
import dev.caulis.protocol.client.fractionalscalev1.WpFractionalScaleV1
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
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

    private fun withServer(mode: WorkspaceMode = WorkspaceMode.PER_MONITOR, test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer(monitorCount = 2, workspaceMode = mode).use { server ->
            TestClient(Connection.connect(server.socketPath)).use { test(server, it) }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val TOP = 1
        const val LEFT = 4
        const val RIGHT = 8
    }
}
