package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.render.assumeGpu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a compositor with workspaces nobody sees has of the desktop: no placing, and the program told. */
class HiddenWindowsTest {

    private class Director : WindowDirector {
        @Volatile var shown = true
        @Volatile var asked = 0
        override fun onScreen(window: Window) = shown
        override fun place(window: Window, now: Long): WindowPlace? {
            asked++
            return WindowPlace(0.0, 0.0, 100.0, 100.0)
        }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    @Test
    fun `a window said not to be on screen is not placed nor found`() = withServer { server, client ->
        val director = Director()
        server.onServer { d -> d.director = director }
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.windowAt(50.0, 50.0) } != null }
        server.onServer { d ->
            director.shown = false
            director.asked = 0
            d.windows.single().setActivated(false) // (a change: a new scene)
            d.requestRedraw()
        }
        client.waitFor { server.onServer { d -> d.windowAt(50.0, 50.0) } == null }
        assertEquals(0, director.asked, "not asked where it is")
    }

    @Test
    fun `fading a picture keeps its buffer's turn`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.surface?.texture != null } }
        server.onServer { d ->
            val surface = d.windows.single().surface
            val picture = DrawItem.Picture(surface, surface.texture!!, dev.caulis.geometry.Rect(0, 0, 10, 10), true, emptyList(), 1f, null, 0f, null, 3)
            val faded = picture.fadedBy(0.5f) as DrawItem.Picture
            assertEquals(3, faded.transform, "still turned")
            assertEquals(0.5f, faded.alpha)
            assertTrue(picture.differsFrom(DrawItem.Picture(surface, surface.texture!!, picture.rect, true, emptyList(), 1f, null, 0f, null, 0)), "another turn is another look")
        }
    }

    @Test
    fun `setSuspended changes only when it changes`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        server.onServer { d ->
            val w = d.windows.single()
            assertEquals(false, w.suspended)
            w.setSuspended(true)
            assertEquals(true, w.suspended)
            w.setSuspended(true)
            w.setSuspended(false)
            assertEquals(false, w.suspended)
        }
    }
}
