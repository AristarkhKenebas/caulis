package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.xdgdecorationunstablev1.ZxdgDecorationManagerV1
import dev.caulis.protocol.client.xdgdecorationunstablev1.ZxdgToplevelDecorationV1
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.tiling.Columns
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Title bars: who draws them, where they are, and what clicking them does. */
class TitleBarsTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    private fun TestClient.decorations(): ZxdgDecorationManagerV1 {
        var found = -1
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                if (`interface` == "zxdg_decoration_manager_v1") found = name
            }
        }
        connection.roundtrip()
        return registry.bind(found, ZxdgDecorationManagerV1, 1)
    }

    // A window that asks for server-side decorations, as Qt or Chromium do; remembers the answer.
    private class Decorated(val window: TestClient.TestWindow) {
        @Volatile var mode = 0
    }

    private fun TestClient.decoratedWindow(manager: ZxdgDecorationManagerV1, appId: String = "app"): Decorated {
        var decoration: ZxdgToplevelDecorationV1? = null
        var mode = 0
        val window = window { toplevel: XdgToplevel ->
            toplevel.setAppId(appId)
            decoration = manager.getToplevelDecoration(toplevel).also { d ->
                d.events = object : ZxdgToplevelDecorationV1.Events {
                    override fun configure(proxy: ZxdgToplevelDecorationV1, mode_: Int) { mode = mode_ }
                }
                d.setMode(ZxdgToplevelDecorationV1.Mode.SERVER_SIDE)
            }
        }
        connection.roundtrip()
        return Decorated(window).also { it.mode = mode }
    }

    @Test
    fun `the compositor's title bar sits on top of the window's tile`() = withServer { server, client ->
        server.onServer {
            it.layout = Columns(Gaps(0, 0))
            it.titleBars = TitleBars.COMPOSITOR
        }
        val decorated = client.decoratedWindow(client.decorations())
        assertEquals(ZxdgToplevelDecorationV1.Mode.SERVER_SIDE, decorated.mode, "the program is told not to draw its own")
        assertEquals(width to height - 30, decorated.window.width to decorated.window.height, "the tile minus the title bar")
        decorated.window.draw()
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect)?.y == 30 } }
        val screen = server.screenshot()
        assertEquals(BAR, screen[15 * width + 5], "the title bar's colour at the top")
        assertEquals(BLACK, screen[100 * width + 100], "the window below it")
    }

    @Test
    fun `programs that draw their own get nothing added, and NONE means no bar at all`() = withServer { server, client ->
        server.onServer {
            it.layout = Columns(Gaps(0, 0))
            it.titleBars = TitleBars.COMPOSITOR
        }
        // A GTK 4 app does not take part: it keeps its own bar and gets the whole tile.
        val own = client.window()
        assertEquals(height, own.height)
        own.draw()
        client.waitFor { server.onServer { d -> d.windows.size == 1 } }
        assertEquals(0, server.onServer { d -> d.titleHeight(d.windows.first()) })

        server.onServer { it.titleBars = TitleBars.NONE }
        val bare = client.decoratedWindow(client.decorations())
        assertEquals(ZxdgToplevelDecorationV1.Mode.SERVER_SIDE, bare.mode, "told to leave its own out...")
        assertEquals(height, bare.window.height, "...and none of ours: the whole tile")
    }

    @Test
    fun `a rule gives one program the compositor's bar, and fullscreen has none`() = withServer { server, client ->
        server.onServer {
            it.layout = Floating
            it.titleBarsFor = { window -> if (window.appId == "spotify") TitleBars.COMPOSITOR else null }
        }
        val manager = client.decorations()
        val other = client.decoratedWindow(manager, "editor")
        val spotify = client.decoratedWindow(manager, "spotify")
        assertEquals(ZxdgToplevelDecorationV1.Mode.CLIENT_SIDE, other.mode)
        assertEquals(ZxdgToplevelDecorationV1.Mode.SERVER_SIDE, spotify.mode)
        spotify.window.draw(300, 200)
        client.waitFor { server.onServer { d -> d.windows.any { it.appId == "spotify" && d.screenRect(it) != null } } }
        assertEquals(30, server.onServer { d -> d.titleHeight(d.windows.first { it.appId == "spotify" }) })

        server.onServer { d -> d.setFullscreen(d.windows.first { it.appId == "spotify" }, true) }
        client.waitFor { spotify.window.width == width && spotify.window.height == height }
        assertEquals(0, server.onServer { d -> d.titleHeight(d.windows.first { it.appId == "spotify" }) }, "nothing over a fullscreen window")
    }

    @Test
    fun `the buttons close and maximize, the bar drags, a double click maximizes`() = withServer { server, client ->
        server.onServer {
            it.layout = Floating
            it.titleBars = TitleBars.COMPOSITOR
            it.animationMillis = 0
        }
        val decorated = client.decoratedWindow(client.decorations())
        decorated.window.draw(300, 200)
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val rect = server.onServer { d -> d.screenRect(d.windows.first())!! }
        fun click(x: Int, y: Int) = server.onServer { d ->
            d.pointerMoved(0, x.toDouble(), y.toDouble())
            d.pointerButton(0, 0x110, true)
            d.pointerButton(0, 0x110, false)
        }
        // Drag the bar: the window follows.
        server.onServer { d ->
            d.pointerMoved(0, rect.x + 50.0, rect.y - 15.0)
            d.pointerButton(0, 0x110, true)
            d.pointerMoved(0, rect.x + 90.0, rect.y + 25.0)
            d.pointerButton(0, 0x110, false)
        }
        val moved = server.onServer { d -> d.screenRect(d.windows.first())!! }
        assertEquals(rect.x + 40 to rect.y + 40, moved.x to moved.y, "moved with the pointer")

        // Double click on the bar: maximized.
        click(moved.x + 50, moved.y - 15)
        click(moved.x + 50, moved.y - 15)
        client.waitFor { XdgToplevel.State.MAXIMIZED in decorated.window.states }

        // The close button is the last one on the right of the bar; it turns red under the pointer.
        val now = server.onServer { d -> d.screenRect(d.windows.first())!! }
        server.onServer { d -> d.pointerMoved(0, now.right - 10.0, now.y - 15.0) }
        assertEquals(CLOSE_RED, server.screenshot()[(now.y - 5) * width + now.right - 30], "lit up red")
        assertFalse(decorated.window.closeRequested)
        click(now.right - 10, now.y - 15)
        client.waitFor { decorated.window.closeRequested }
        assertTrue(decorated.window.closeRequested, "asked to close")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val BAR = 0xFF2B2F38.toInt() // TitleBarStyle's focused background
        const val CLOSE_RED = 0xFFE81123.toInt()
    }
}
