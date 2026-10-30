package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.render.Color
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Gaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Rounded corners, borders, shadows and transparency on windows, checked on the drawn pixels. */
class EffectsTest {

    private val width = ScreenTestServer.WIDTH

    @Test
    fun `round corners and a border around the focused window`() = withWindow(
        Effects(cornerRadius = 16, border = Border(width = 4, focused = Color(1f, 0f, 0f), unfocused = Color(0f, 1f, 0f))),
    ) { screen, rect ->
        val background = screen.at(5, 5)
        assertEquals(BLACK, screen.at(rect.x + rect.width / 2, rect.y + rect.height / 2), "the window's picture")
        assertEquals(background, screen.at(rect.x + 1, rect.y + 1), "its corner is cut away")
        assertEquals(RED, screen.at(rect.x + rect.width / 2, rect.y - 2), "the focus border above it")
        assertEquals(RED, screen.at(rect.x - 2, rect.y + rect.height / 2), "and left of it")
    }

    @Test
    fun `a shadow below the window fades out`() = withWindow(Effects(shadow = Shadow(softness = 30, offsetY = 0))) { screen, rect ->
        val background = screen.at(5, 5)
        val x = rect.x + rect.width / 2
        val near = screen.at(x, rect.bottom + 3)
        assertTrue(brightness(near) < brightness(background), "darker right below the window")
        assertEquals(background, screen.at(x, rect.bottom + 40), "gone further away")
    }

    @Test
    fun `other windows can be see-through and fullscreen ones never get effects`() = withServer { server, client ->
        server.onServer {
            it.layout = Columns(Gaps(0, 0))
            it.effects = Effects(inactiveOpacity = 0.5f, cornerRadius = 20)
        }
        val first = client.window()
        first.draw()
        val second = client.window()
        second.draw()
        client.waitFor { first.width == width / 2 && second.width == width / 2 }
        first.draw()
        second.draw()
        client.waitFor { server.onServer { d -> d.windows.size == 2 && d.windows.all { d.screenRect(it)?.width == width / 2 } } }
        val (inactive, active) = server.onServer { d -> d.windows.map { d.screenRect(it)!! } }
        val screen = server.screenshot()
        val background = screen.at(0, 0) // a cut corner
        // Black at half strength over the background: in between.
        val middle = { r: Rect -> screen.at(r.x + r.width / 2, r.y + r.height / 2) }
        assertEquals(BLACK, middle(active), "the focused window is solid")
        assertTrue(brightness(middle(inactive)) in 1 until brightness(background), "the other one is half see-through: ${Integer.toHexString(middle(inactive))}")

        server.onServer { d -> d.setFullscreen(d.focusedWindow!!, true) }
        client.waitFor { second.width == width }
        second.draw()
        client.waitFor { server.screenshot().at(0, 0) == BLACK }
        assertNotEquals(background, server.screenshot().at(0, 0), "no rounded corner on a fullscreen window")
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]
    private fun brightness(pixel: Int) = ((pixel shr 16) and 0xff) + ((pixel shr 8) and 0xff) + (pixel and 0xff)

    // One window with [effects]; [check] gets the screen's pixels and where the window is.
    private fun withWindow(effects: Effects, check: (IntArray, Rect) -> Unit) = withServer { server, client ->
        server.onServer { it.effects = effects }
        val window = client.window()
        window.draw(200, 150)
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val rect = server.onServer { d -> d.screenRect(d.windows.first())!! }
        check(server.screenshot(), rect)
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
