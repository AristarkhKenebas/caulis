package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Special workspaces (Hyprland's "special"): over the current workspace, toggled, windows moved in and out. */
class SpecialWorkspaceTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    @Test
    fun `its look says where its windows come from`() {
        val r = Rect(100, 100, 1000, 500).let { SpecialLook(fade = true, scale = 0.94, rise = 10).hidden(it) }
        assertEquals(Rect(130, 125, 940, 470), r)
        assertFailsWith<IllegalArgumentException> { SpecialLook(scale = 0.0) }
    }

    @Test
    fun `with animations it fades in and out, and hides at once for the lock`() = withServer { server, client ->
        client.window { it.setTitle("special one") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        server.onServer { d ->
            d.specialWorkspaces = SpecialWorkspaces(look = SpecialLook(fade = true, scale = 0.9))
            d.animations = Animations(entries = mapOf("scratchpads" to AnimationEntry(enabled = true, millis = 2000)))
            d.moveToSpecialWorkspace(d.windows.single())
        }
        // Hiding takes its time; shown again meanwhile it stays; the lock ends it at once.
        assertFalse(server.onServer { d -> d.toggleSpecialWorkspace() })
        assertTrue(server.onServer { d -> d.specialWorkspace().shown }, "still going")
        assertTrue(server.onServer { d -> d.toggleSpecialWorkspace() }, "back while going")
        server.onServer { d -> d.lockInternally(object : Widget {
            override fun area(monitor: Monitor) = null
            override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) {}
        }, hideDesktop = true) }
        assertFalse(server.onServer { d -> d.specialWorkspace().shown }, "gone at once for the lock")
        server.onServer { d -> d.unlockInternally() }
    }

    @Test
    fun `toggled it shows over the workspace with the focus in it, and hides with the focus back`() = withServer { server, client ->
        val normal = client.window { it.setTitle("normal") }.also { it.draw() }
        val other = client.window { it.setTitle("special one") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        server.onServer { d -> d.moveToSpecialWorkspace(d.windows.single { it.title == "special one" }) }
        client.waitFor { other.width == width && other.height == height }
        assertEquals("special one", server.onServer { d -> d.focusedWindow?.title }, "the focus went in")
        assertTrue(server.onServer { d -> d.specialWorkspace().shown })
        assertEquals(1, server.onServer { d -> d.windows.size }, "it left the workspace")

        // Hidden: the focus goes back to the workspace; shown: into it; hidden: back to the window that had it.
        assertFalse(server.onServer { d -> d.toggleSpecialWorkspace() }, "hidden now")
        assertEquals("normal", server.onServer { d -> d.focusedWindow?.title })
        assertTrue(server.onServer { d -> d.toggleSpecialWorkspace() }, "shown again")
        assertEquals("special one", server.onServer { d -> d.focusedWindow?.title })
        assertFalse(server.onServer { d -> d.toggleSpecialWorkspace() }, "hidden again")
        assertEquals("normal", server.onServer { d -> d.focusedWindow?.title }, "the focus came back")
        assertFalse(server.onServer { d -> d.specialWorkspace().shown })

        // Shown again: independent of the normal workspaces.
        server.onServer { d -> d.switchTo(3) }
        assertTrue(server.onServer { d -> d.toggleSpecialWorkspace("special") })
        assertEquals("special one", server.onServer { d -> d.focusedWindow?.title })
        assertEquals(3, server.onServer { d -> d.activeWorkspace.number }, "the workspace stays")
        normal.draw()
    }

    @Test
    fun `windows move into it silently and out again, tiled by the layout`() = withServer { server, client ->
        val a = client.window { it.setTitle("a") }.also { it.draw() }
        val b = client.window { it.setTitle("b") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        server.onServer { d -> d.moveToSpecialWorkspace(d.windows.single { it.title == "a" }, "notes", silent = true) }
        assertFalse(server.onServer { d -> d.specialWorkspace("notes").shown }, "silent: not shown")
        assertEquals("b", server.onServer { d -> d.focusedWindow?.title }, "the focus stays")
        server.onServer { d -> d.moveToSpecialWorkspace(d.windows.single { it.title == "b" }, "special:notes") }
        assertTrue(server.onServer { d -> d.specialWorkspace("notes").shown }, "\"special:notes\" is \"notes\"")
        // Two windows side by side (Dwindle), inside the monitor.
        client.waitFor { a.width == width / 2 && b.width == width / 2 }
        assertEquals(0, server.onServer { d -> d.windows.size })

        // Out again: onto the workspace on screen, with the focus; the other one fills it now.
        server.onServer { d -> assertTrue(d.moveOutOfSpecialWorkspace(d.specialWorkspace("notes").windows.single { it.title == "b" })) }
        assertEquals(listOf("b"), server.onServer { d -> d.windows.map { it.title } })
        assertEquals("b", server.onServer { d -> d.focusedWindow?.title })
        client.waitFor { a.width == width }
        // The last one out: the empty special workspace hides.
        server.onServer { d -> d.moveOutOfSpecialWorkspace(d.specialWorkspace("notes").windows.single()) }
        assertFalse(server.onServer { d -> d.specialWorkspace("notes").shown })
        assertEquals(2, server.onServer { d -> d.windows.size })
        a.draw()
        b.draw()
    }

    @Test
    fun `a rule sends an app there, and a new window joins the shown one`() = withServer { server, client ->
        server.onServer { d -> d.placementFor = { w -> if (w.appId == "music") WindowPlacement(specialWorkspace = "music") else null } }
        val music = client.window { it.setAppId("music") }
        assertEquals(width to height, music.width to music.height, "its size before the first frame")
        music.draw()
        client.waitFor { server.onServer { d -> d.specialWorkspace("music").windows.size } == 1 }
        assertFalse(server.onServer { d -> d.specialWorkspace("music").shown }, "waiting there, hidden")

        server.onServer { d -> d.toggleSpecialWorkspace("music") }
        val more = client.window { it.setTitle("more") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.specialWorkspace("music").windows.size } == 2 }
        assertEquals("more", server.onServer { d -> d.focusedWindow?.title })
        assertTrue(server.onServer { d -> d.windows.isEmpty() })

        // Closing them: the last one hides it.
        more.toplevel.destroy()
        more.surface.destroy()
        music.toplevel.destroy()
        music.surface.destroy()
        client.connection.roundtrip()
        client.waitFor { !server.onServer { d -> d.specialWorkspace("music").shown } }
    }

    @Test
    fun `two named ones, one per monitor at a time, and the lock hides them`() = withServer { server, client ->
        val one = client.window { it.setTitle("one") }.also { it.draw() }
        val two = client.window { it.setTitle("two") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        server.onServer { d ->
            d.moveToSpecialWorkspace(d.windows.single { it.title == "one" }, "first", silent = true)
            d.moveToSpecialWorkspace(d.windows.single { it.title == "two" }, "second", silent = true)
        }
        assertEquals(listOf("first", "second"), server.onServer { d -> d.specialWorkspaceNames })
        server.onServer { d -> d.toggleSpecialWorkspace("first") }
        assertEquals("one", server.onServer { d -> d.focusedWindow?.title })
        server.onServer { d -> d.toggleSpecialWorkspace("second") }
        assertFalse(server.onServer { d -> d.specialWorkspace("first").shown }, "the second took its place")
        assertEquals("two", server.onServer { d -> d.focusedWindow?.title })
        assertSame(server.onServer { d -> d.specialWorkspace("second") }, server.onServer { d -> d.shownSpecialOn(d.focusedMonitor) })

        // The lock: hidden (and the screen shows the lock, not it).
        val screen = object : Widget {
            override fun area(monitor: Monitor) = Rect(0, 0, 10, 10)
            override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) = canvas.fill(area, 0f, 0f, 1f)
        }
        server.onServer { d -> d.lockInternally(screen) }
        assertNull(server.onServer { d -> d.shownSpecialOn(d.focusedMonitor) })
        assertFalse(server.onServer { d -> d.toggleSpecialWorkspace("second") }, "not shown while locked")
        assertNull(server.onServer { d -> d.shownSpecialOn(d.focusedMonitor) })
        assertNull(server.onServer { d -> d.keyboardFocus?.roleObject as? Window }, "no window behind the lock gets the keyboard")
        server.onServer { d -> d.unlockInternally() }
        one.draw()
        two.draw()
    }

    @Test
    fun `toggled on another monitor it comes over there`() = withServer(monitors = 2) { server, client ->
        val one = client.window { it.setTitle("one") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        server.onServer { d ->
            d.moveToSpecialWorkspace(d.windows.single(), "s", silent = true)
            val (m0, m1) = d.monitors
            d.focusMonitor(m0)
            assertTrue(d.toggleSpecialWorkspace("s"))
            assertSame(d.specialWorkspace("s"), d.shownSpecialOn(m0))
            d.focusMonitor(m1)
            assertTrue(d.toggleSpecialWorkspace("s"), "shown, over here now")
            assertSame(d.specialWorkspace("s"), d.shownSpecialOn(m1))
            assertNull(d.shownSpecialOn(m0))
            // And show (not toggle) brings it back.
            d.focusMonitor(m0)
            d.showSpecialWorkspace("s")
            assertSame(d.specialWorkspace("s"), d.shownSpecialOn(m0))
            assertNull(d.shownSpecialOn(m1))
            // Toggled where it is shown: hidden.
            assertFalse(d.toggleSpecialWorkspace("s"))
            assertNull(d.shownSpecialOn(m0))
        }
        one.draw()
    }

    @Test
    fun `its monitor gone, one special workspace a monitor stays`() = withServer(monitors = 2) { server, client ->
        val one = client.window { it.setTitle("one") }.also { it.draw() }
        val two = client.window { it.setTitle("two") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        server.onServer { d ->
            d.moveToSpecialWorkspace(d.windows.single { it.title == "one" }, "first", silent = true)
            d.moveToSpecialWorkspace(d.windows.single { it.title == "two" }, "second", silent = true)
            val (m0, m1) = d.monitors
            d.focusMonitor(m0)
            d.toggleSpecialWorkspace("first")
            d.focusMonitor(m1)
            d.toggleSpecialWorkspace("second")
            d.removeMonitor(m1)
            val left = d.monitors.single()
            assertSame(d.specialWorkspace("first"), d.shownSpecialOn(left), "the one shown there stays")
            assertFalse(d.specialWorkspace("second").shown && !d.specialWorkspace("second").leaving, "the other hides")
        }
        one.draw()
        two.draw()
    }

    @Test
    fun `a rule naming a special workspace with spaces is refused, the window opens as usual`() = withServer { server, client ->
        assertFailsWith<IllegalArgumentException> { WindowPlacement(specialWorkspace = "my music") }
        assertFailsWith<IllegalArgumentException> { WindowPlacement(specialWorkspace = "special: x y") }
        WindowPlacement(specialWorkspace = "special:music") // fine
        server.onServer { d -> d.placementFor = { WindowPlacement(specialWorkspace = "my music") } }
        val window = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        assertTrue(server.onServer { d -> d.specialWorkspaceNames.isEmpty() })
        window.draw()
    }

    private fun withServer(monitors: Int = 1, test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer(monitorCount = monitors).use { server ->
            server.onServer { d ->
                d.layout = Dwindle(Gaps(inner = 0, outer = 0))
                d.specialWorkspaces = SpecialWorkspaces(gap = 0)
            }
            TestClient(Connection.connect(server.socketPath)).use { test(server, it) }
        }
    }
}
