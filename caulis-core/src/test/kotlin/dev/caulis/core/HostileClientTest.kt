package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.wayland.WlSubcompositor
import dev.caulis.protocol.client.xdgshell.XdgPopup
import dev.caulis.render.assumeGpu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Clients are not to be trusted: requests meant to crash the compositor, steal the keyboard
 * or bring back a dead window are refused — and everybody else goes on as before.
 */
class HostileClientTest {

    @Test
    fun `a popup cannot be its own parent`() = withServer { server ->
        hostile(server) { client ->
            val surface = client.compositor.createSurface()
            val xdg = client.wmBase.getXdgSurface(surface)
            xdg.getPopup(xdg, positioner(client))
            surface.commit()
            val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
            assertTrue("parent" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `a surface gets one sub-surface at a time, and parents never loop`() = withServer { server ->
        hostile(server) { client ->
            val sub = client.bind("wl_subcompositor", WlSubcompositor, 1)
            val (a, b, root) = List(3) { client.compositor.createSurface() }
            sub.getSubsurface(a, root)
            sub.getSubsurface(b, a)
            sub.getSubsurface(b, root) // a second one for b
            assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        }
    }

    @Test
    fun `asking a sub-surface's place again and again without a commit does not pile up`() = withServer { server ->
        TestClient(Connection.connect(server.socketPath)).use { client ->
            val window = client.window().also { it.draw() }
            client.waitFor { server.onServer { it.windows.size } == 1 }
            val sub = client.bind("wl_subcompositor", WlSubcompositor, 1)
            val (a, b) = List(2) { client.compositor.createSurface() }
            val subA = sub.getSubsurface(a, window.surface)
            sub.getSubsurface(b, window.surface)
            repeat(1000) { subA.placeAbove(b) }
            client.connection.roundtrip()
            assertEquals(1, server.onServer { it.windows.single().surface.pendingPlacements.size }, "one request kept: the last")
            window.surface.commit()
            client.connection.roundtrip()
            assertEquals(0, server.onServer { it.windows.single().surface.pendingPlacements.size })
        }
    }

    @Test
    fun `a toplevel destroyed stays gone, even if its surface commits a picture again`() = withServer { server ->
        TestClient(Connection.connect(server.socketPath)).use { client ->
            val window = client.window().also { it.draw() }
            client.waitFor { server.onServer { it.windows.size } == 1 }
            window.toplevel.destroy()
            client.waitFor { server.onServer { it.windows.isEmpty() } }
            window.draw(100, 100)
            client.connection.roundtrip()
            Thread.sleep(100)
            assertEquals(0, server.onServer { it.allWindows.size }, "no ghost window")
        }
    }

    @Test
    fun `a program in the background cannot take the keyboard with a menu grab`() = withServer { server ->
        TestClient(Connection.connect(server.socketPath)).use { user ->
            TestClient(Connection.connect(server.socketPath)).use { background ->
                val behind = background.window { it.setAppId("behind") }.also { it.draw() }
                background.waitFor { server.onServer { it.focusedWindow?.appId } == "behind" }
                user.window { it.setAppId("in use") }.also { it.draw() }
                user.waitFor { server.onServer { it.focusedWindow?.appId } == "in use" }
                val focused = server.onServer { it.focusedWindow }

                val surface = background.compositor.createSurface()
                var dismissed = false
                background.wmBase.getXdgSurface(surface).getPopup(behind.xdgSurface, positioner(background)).also { popup ->
                    popup.events = object : XdgPopup.Events {
                        override fun popupDone(proxy: XdgPopup) { dismissed = true }
                    }
                    popup.grab(background.seat, 0)
                }
                background.waitFor { dismissed }
                assertEquals(focused, server.onServer { it.focusedWindow }, "the keyboard stayed with the program in use")
            }
        }
    }

    @Test
    fun `programs that are not privileged see no screenshots, clipboard managers or panels`() = withServer { server ->
        server.onServer { server.display.privileged = { client -> client.program == "nobody" } }
        TestClient(Connection.connect(server.socketPath)).use { client ->
            assertEquals(null, client.layerShell, "no panels")
            for (name in listOf("zwlr_screencopy_manager_v1", "ext_image_copy_capture_manager_v1", "ext_data_control_manager_v1", "zwlr_output_manager_v1", "ext_session_lock_manager_v1")) {
                assertTrue(runCatching { client.bind(name, dev.caulis.protocol.client.wayland.WlCallback, 1) }.isFailure, "$name is not offered")
            }
            client.window().draw()
            client.waitFor { server.onServer { it.windows.size } == 1 } // the usual still works
        }
    }

    private fun positioner(client: TestClient) = client.wmBase.createPositioner().apply {
        setSize(100, 50)
        setAnchorRect(0, 0, 10, 10)
    }

    // A client that misbehaves in [act] (and is thrown out); afterwards a well-behaved one still works.
    private fun hostile(server: ScreenTestServer, act: (TestClient) -> Unit) {
        TestClient(Connection.connect(server.socketPath)).use(act)
        TestClient(Connection.connect(server.socketPath)).use { client ->
            client.window().draw()
            client.waitFor { server.onServer { it.windows.size } == 1 }
            Thread.sleep(100) // a frame or two drawn after it
            assertEquals(1, server.onServer { it.windows.size }, "the compositor goes on")
        }
    }

    private fun withServer(test: (ScreenTestServer) -> Unit) {
        assumeGpu()
        ScreenTestServer().use(test)
    }
}
