package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.render.assumeGpu
import kotlin.math.abs
import kotlin.test.Test

/** A view over the background: only its part is shown, stretched over the monitor. */
class BackgroundViewTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `the right half of a red and blue background fills the whole monitor`() = withServer { server, _ ->
        server.onServer { d ->
            d.background = Background.painted { g, look ->
                g.color = java.awt.Color.RED
                g.fillRect(0, 0, look.width / 2, look.height)
                g.color = java.awt.Color.BLUE
                g.fillRect(look.width / 2, 0, look.width - look.width / 2, look.height)
            }
        }
        waitFor(server, 20, height / 2, RED)
        server.onServer { d -> d.setBackgroundView(d.monitors.first(), BackgroundView(0.5, 0.0, 0.5, 1.0)) }
        waitFor(server, 20, height / 2, BLUE)
        waitFor(server, width - 20, height / 2, BLUE)
        server.onServer { d -> d.setBackgroundView(d.monitors.first(), null) }
        waitFor(server, 20, height / 2, RED)
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
        const val BLUE = 0x0000FF
    }
}
