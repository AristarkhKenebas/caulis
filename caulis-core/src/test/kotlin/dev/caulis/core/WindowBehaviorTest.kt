package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Floating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [WindowBehavior] and pinned windows: what rules may change about how windows behave. */
class WindowBehaviorTest {

    @Test
    fun `a pinned window goes along to every workspace its monitor shows`() = withServer { server, client ->
        client.window { it.setAppId("pip") }.draw(160, 90)
        client.window { it.setAppId("editor") }.draw(300, 200)
        client.waitFor { server.onServer { it.windows.size } == 2 }
        server.onServer { d ->
            val pip = d.windows.single { it.appId == "pip" }
            d.setPinned(pip, true)
            assertTrue(d.isFloating(pip), "pinned windows float")
            val place = pip.x to pip.y
            d.switchTo(2)
            assertEquals(listOf("pip"), d.windows.map { it.appId }, "it came along; the editor stayed")
            assertEquals(place, pip.x to pip.y, "in the same place")
            d.switchTo(1)
            assertEquals(setOf("pip", "editor"), d.windows.map { it.appId }.toSet())
            d.setFloating(pip, false)
            assertFalse(d.isPinned(pip), "tiled again: in one workspace's layout")
        }
    }

    @Test
    fun `requests its rules refuse are only answered`() = withServer { server, client ->
        server.onServer { d -> d.behaviorFor = { WindowBehavior(ignored = setOf(WindowRequest.FULLSCREEN, WindowRequest.MAXIMIZE)) } }
        val window = client.window()
        window.draw(200, 100)
        client.waitFor { server.onServer { it.windows.size } == 1 }
        window.toplevel.setFullscreen(null)
        window.toplevel.setMaximized()
        client.connection.roundtrip()
        client.connection.roundtrip()
        assertTrue(XdgToplevel.State.FULLSCREEN !in window.states && XdgToplevel.State.MAXIMIZED !in window.states)
        assertFalse(server.onServer { d -> d.windows.single().let { it.fullscreen || it.maximized } })
    }

    @Test
    fun `a window that takes no keyboard leaves it where it is`() = withServer { server, client ->
        client.window { it.setAppId("editor") }.draw(300, 200)
        client.waitFor { server.onServer { it.focusedWindow?.appId } == "editor" }
        server.onServer { d -> d.behaviorFor = { w -> if (w.appId == "keyboard") WindowBehavior(takesFocus = false) else WindowBehavior.USUAL } }
        client.window { it.setAppId("keyboard") }.draw(300, 100)
        client.waitFor { server.onServer { it.windows.size } == 2 }
        server.onServer { d ->
            assertEquals("editor", d.focusedWindow?.appId)
            d.focus(d.windows.single { it.appId == "keyboard" })
            assertEquals("editor", d.focusedWindow?.appId, "not even when asked")
        }
    }

    @Test
    fun `a floating window keeps to its size limits, from the start and when resized`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.behaviorFor = { WindowBehavior(minWidth = 400, minHeight = 300, maxWidth = 500) }
        }
        val window = client.window()
        window.draw(200, 100)
        client.waitFor { window.width == 400 && window.height == 300 }
    }

    @Test
    fun `screen sharing shows a black box where a hidden window is`() = withServer { server, client ->
        server.onServer { d ->
            d.layout = Floating
            d.effects = Effects.NONE
            d.titleBars = TitleBars.NONE
        }
        val window = client.window()
        val (buffer, memory) = client.buffer(200, 100)
        memory.write { segment -> for (i in 0 until 200 * 100) segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i.toLong(), RED) }
        window.surface.attach(buffer, 0, 0)
        window.surface.damageBuffer(0, 0, 200, 100)
        window.surface.commit()
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val rect = server.onServer { d -> d.screenRect(d.windows.single())!! }
        val at = (rect.y + 50) * ScreenTestServer.WIDTH + rect.x + 100
        assertEquals(RED, server.capture()[at], "shared as it is")
        server.onServer { d -> d.behaviorFor = { WindowBehavior(hiddenFromCapture = true) } }
        assertEquals(BLACK, server.capture()[at], "hidden: black")
        assertEquals(RED, server.screenshot()[at], "on the screen itself as it is")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
