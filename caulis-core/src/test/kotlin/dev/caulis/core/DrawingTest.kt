package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.render.Color
import dev.caulis.render.Paint
import dev.caulis.render.assumeGpu
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pictures of your own that take no input: on top of a window, the pointer still finds the window. */
class DrawingTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `a drawing on top is seen but clicks go through, a director's border goes above its window`() = withServer { server, client ->
        server.onServer { d ->
            d.animationMillis = 0
            d.director = object : WindowDirector {
                override fun place(window: Window, now: Long) = WindowPlace(100.0, 100.0, 200.0, 150.0)
                override fun decorate(window: Window, place: WindowPlace, canvas: OverlayCanvas, above: Boolean) {
                    if (above) canvas.border(place.rect, 4f, Paint.solid(Color(0f, 1f, 0f)))
                }
            }
            d.addDrawing(DrawingLevel.ON_TOP, object : Drawing {
                override fun draw(monitor: Monitor, canvas: OverlayCanvas) = canvas.fill(Rect(150, 150, 40, 40), 1f, 0f, 0f)
            })
        }
        val window = client.window()
        window.draw(200, 150)
        client.waitFor { server.onServer { d -> d.windowAt(160.0, 160.0) } != null }
        assertEquals(server.onServer { d -> d.windows.single() }, server.onServer { d -> d.windowAt(160.0, 160.0) }, "under the red square: the window")
        waitFor(server, 160, 160, RED)
        waitFor(server, 101, 200, GREEN)
        assertEquals(0, server.screenshot()[200 * width + 200] and 0xFFFFFF, "the window itself (black) inside the border")
    }

    private fun waitFor(server: ScreenTestServer, x: Int, y: Int, color: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = server.screenshot()[y * width + x]
            if ((0..2).all { s -> abs((seen shr (s * 8) and 0xff) - (color shr (s * 8) and 0xff)) <= 3 }) return
            Thread.sleep(30)
        }
        error("at $x,$y: ${Integer.toHexString(seen)}, not ${Integer.toHexString(color)}")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val RED = 0xFF0000
        const val GREEN = 0x00FF00
    }
}
