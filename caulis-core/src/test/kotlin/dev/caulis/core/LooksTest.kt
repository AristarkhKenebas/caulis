package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dimmed windows without the focus, the desktop dimmed behind a panel, and the screen shader — as shown on screen. */
class LooksTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `windows without the focus are darker, as much as dimInactive says`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Dwindle(Gaps(0, 0))
            d.effects = Effects(dimInactive = 0.5f, focusChangeMillis = 0)
            d.titleBars = TitleBars.NONE
        }
        val windows = listOf(client.window(), client.window())
        windows.forEach { red(client, it) }
        client.waitFor { server.onServer { it.windows.size } == 2 }
        client.connection.roundtrip()
        windows.forEach { red(client, it) } // (in the sizes they have now, side by side)
        Thread.sleep(200)
        val shot = server.screenshot()
        // (Away from the pointer in the middle.)
        fun redAt(x: Int) = (shot[40 * width + x] shr 16) and 0xff
        val focusedOnLeft = server.onServer { d -> d.screenRect(d.focusedWindow!!)!!.x == 0 }
        val (focused, other) = if (focusedOnLeft) 40 to width - 40 else width - 40 to 40
        assertEquals(255, redAt(focused), "the focused one as it is")
        assertTrue(redAt(other) in 120..135, "the other one half as bright: ${redAt(other)}")
    }

    @Test
    fun `a panel can dim everything behind it`() = withServer { server, client ->
        server.onServer { d -> d.layerBehaviorFor = { LayerBehavior(dimAround = 0.5f) } }
        val before = server.screenshot()[10 * width + 10]
        val surface = client.compositor.createSurface()
        val layer = client.layerShell!!.getLayerSurface(surface, null, 3, "launcher")
        var size = 0 to 0
        layer.events = object : ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) {
                size = width to height
                proxy.ackConfigure(serial)
            }
        }
        layer.setSize(100, 50)
        surface.commit()
        client.waitFor { size.first > 0 }
        client.draw(surface, size.first, size.second)
        client.waitFor { server.screenshot()[10 * width + 10] != before }
        val after = server.screenshot()[10 * width + 10]
        assertEquals(((before shr 8) and 0xff) / 2, (after shr 8) and 0xff, "half as bright behind it")
    }

    @Test
    fun `the screen shader colours every frame shown`() = withServer { server, client ->
        val shown = java.util.concurrent.atomic.AtomicInteger()
        server.onFrameShown = { _, pixels -> shown.set(pixels[10 * width + 10]) }
        server.onServer { d -> d.screenShader = ScreenShader.INVERT }
        client.waitFor { shown.get() == INVERTED_BACKGROUND }
        server.onServer { d -> d.screenShader = ScreenShader("this is not GLSL") }
        client.waitFor { shown.get() == BACKGROUND } // (said in the log; the screen as it is)
        server.onFrameShown = null
    }

    // A window drawn all red.
    private fun red(client: TestClient, window: TestClient.TestWindow) {
        val (w, h) = (window.width.takeIf { it > 0 } ?: 100) to (window.height.takeIf { it > 0 } ?: 100)
        val (buffer, memory) = client.buffer(w, h)
        memory.write { segment -> for (i in 0 until w * h) segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i.toLong(), 0xFFFF0000.toInt()) }
        window.surface.attach(buffer, 0, 0)
        window.surface.damageBuffer(0, 0, w, h)
        window.surface.commit()
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BACKGROUND = 0xFF292E38.toInt()
        const val INVERTED_BACKGROUND = 0xFFD6D1C7.toInt()
    }
}
