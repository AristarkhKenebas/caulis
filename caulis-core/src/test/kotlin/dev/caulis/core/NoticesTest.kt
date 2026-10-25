package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The compositor's own notices: where they are, what makes way for them, how they go. */
class NoticesTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `a banner at the top pushes windows down, a click closes it and they come back`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Columns(Gaps(0, 0))
            d.animationMillis = 0
        }
        client.window().draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.y } == 0 }

        server.onServer { d -> d.notify("keys.close and keys.float are both Super+V", NoticeLevel.WARNING) }
        val room = server.onServer { d -> d.monitors[0].usableArea.y }
        assertTrue(room in 20..80, "the strip takes room at the top: $room")
        client.waitFor { server.onServer { d -> d.windows.single().y } == room }
        waitForColor(server, 5, room / 2, YELLOW)

        // A click on it: gone, and the window has all the room again.
        server.onServer { d ->
            d.pointerMoved(0, 300.0, room / 2.0)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertTrue(d.notices.isEmpty())
            assertEquals(0, d.monitors[0].usableArea.y)
        }
        client.waitFor { server.onServer { d -> d.windows.single().y } == 0 }
    }

    @Test
    fun `panels move below a banner, or are covered by it`() = withServer { server, client ->
        val surface = client.compositor.createSurface()
        val bar = client.layerShell!!.getLayerSurface(surface, null, 2, "bar")
        bar.events = object : ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) = proxy.ackConfigure(serial)
        }
        bar.setSize(0, 30)
        bar.setAnchor(TOP or LEFT or RIGHT)
        bar.setExclusiveZone(30)
        surface.commit()
        client.connection.roundtrip()
        client.draw(surface, width, 30)
        client.waitFor { server.onServer { d -> d.monitors[0].usableArea.y } == 30 }

        val room = server.onServer { d ->
            d.notify("A mistake", NoticeLevel.ERROR)
            d.monitors[0].usableArea.y - 30
        }
        assertTrue(room > 0, "panel and windows both moved down")
        assertEquals(room, server.onServer { d -> d.layers.single().y }, "the bar right below the strip")

        // Over the panel, windows still make way.
        server.onServer { d ->
            d.noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(room = NoticePlacement.Banner.Room.PUSH_WINDOWS))
            assertEquals(0, d.layers.single().y)
            assertEquals(maxOf(30, room), d.monitors[0].usableArea.y)
            // Over everything: nothing moves.
            d.noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(room = NoticePlacement.Banner.Room.COVER))
            assertEquals(30, d.monitors[0].usableArea.y)
        }
        waitForColor(server, 5, 10, RED)
    }

    @Test
    fun `toasts in a corner take no room, the same one twice is counted`() = withServer { server, _ ->
        val (usable, count) = server.onServer { d ->
            d.noticeStyle = NoticeStyle(placement = NoticePlacement.Toast(width = 300, margin = 16), info = MAGENTA_LOOK)
            d.notify("Recording started")
            val again = d.notify("Recording started")
            d.monitors[0].usableArea to again.count
        }
        assertEquals(Rect(0, 0, width, height), usable)
        assertEquals(2, count)
        assertEquals(1, server.onServer { d -> d.notices.size })
        waitForColor(server, width - 16 - 150, height - 16 - 5, MAGENTA)
    }

    @Test
    fun `notices go by themselves after their time, hidden ones only reach listeners`() = withServer { server, client ->
        val heard = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.onServer { d ->
            d.noticeStyle = NoticeStyle(infoSeconds = 0.2)
            d.notify("Soon gone")
        }
        client.waitFor { server.onServer { d -> d.notices.isEmpty() } }
        client.waitFor { server.onServer { d -> d.monitors[0].usableArea.y } == 0 }

        server.onServer { d ->
            d.noticeStyle = NoticeStyle(placement = NoticePlacement.Hidden, info = MAGENTA_LOOK)
            d.onNotice { heard += it.text }
            d.notify("Only for you")
            assertEquals(0, d.monitors[0].usableArea.y, "no strip")
        }
        assertEquals(listOf("Only for you"), heard)
        val picture = server.screenshot()
        val grey = picture.withIndex().filter { near(it.value, MAGENTA) }
        assertTrue(grey.isEmpty(), "nothing drawn, but ${grey.size} grey pixels, first at ${grey.firstOrNull()?.index?.let { "${it % width},${it / width}" }}: ${grey.firstOrNull()?.value?.let(Integer::toHexString)}")
    }

    // Waits until the notice has faded in and the pixel at ([x], [y]) has its colour.
    private fun waitForColor(server: ScreenTestServer, x: Int, y: Int, color: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = server.screenshot()[y * width + x]
            if (near(seen, color)) return
            Thread.sleep(30)
        }
        error("at $x,$y: ${Integer.toHexString(seen)}, not ${Integer.toHexString(color)}")
    }

    private fun near(a: Int, b: Int) = (0..2).all { shift -> abs((a shr (shift * 8) and 0xff) - (b shr (shift * 8) and 0xff)) <= 3 }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { test(server, it) }
        }
    }

    private companion object {
        const val YELLOW = 0xFFF5C211.toInt()
        const val RED = 0xFFE01B24.toInt()
        const val MAGENTA = 0xFFFF00FF.toInt()

        // A colour no other part of the desktop has.
        val MAGENTA_LOOK = NoticeColors(dev.caulis.render.Color.hex("#ff00ff"), dev.caulis.render.Color.hex("#000000"))
        const val BTN_LEFT = 0x110
        const val TOP = 1
        const val LEFT = 4
        const val RIGHT = 8
    }
}
