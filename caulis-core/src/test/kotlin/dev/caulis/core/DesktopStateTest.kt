package dev.caulis.core

import dev.caulis.protocol.client.Connection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop's own bookkeeping stays right: focus, workspaces that come and go, closed windows. */
class DesktopStateTest {

    @Test
    fun `a pinned window does not take the keyboard on every workspace switch`() = withServer { server, client ->
        client.window { it.setAppId("pip") }.draw(100, 60)
        client.waitFor { server.onServer { it.windows.size } == 1 }
        server.onServer { d -> d.setPinned(d.windows.single(), true); d.switchTo(2) }
        client.window { it.setAppId("editor") }.draw()
        client.waitFor { server.onServer { it.focusedWindow?.appId } == "editor" }
        server.onServer { d ->
            d.switchTo(1)
            d.switchTo(2)
            assertEquals("editor", d.focusedWindow?.appId, "the editor has it again, not the pinned one")
        }
    }

    @Test
    fun `a placement on a workspace that does not exist is not used`() = withServer { server, client ->
        assertFailsWith<IllegalArgumentException> { WindowPlacement(workspace = 0) }
        server.onServer { d -> d.placementFor = { WindowPlacement(workspace = 42) } }
        client.window().draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        server.onServer { d -> assertEquals(1, d.workspaceNumberOf(d.windows.single())) }
    }

    @Test
    fun `an empty workspace nobody sees goes away, also when its last window closes there`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { it.windows.size } == 1 }
        server.onServer { d ->
            d.moveToWorkspace(d.windows.single(), 5)
            assertTrue(5 in d.allWorkspaces.map { it.number })
        }
        window.toplevel.destroy()
        window.surface.destroy()
        client.waitFor { server.onServer { d -> d.allWindows.isEmpty() } }
        server.onServer { d -> assertFalse(5 in d.allWorkspaces.map { it.number }, "workspace 5 is gone") }
    }

    @Test
    fun `a closed window leaves nothing behind`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val closed = server.onServer { it.windows.single() }
        window.toplevel.destroy()
        window.surface.destroy()
        client.waitFor { server.onServer { d -> d.allWindows.isEmpty() } }
        server.onServer { d -> assertNull(d.screenRect(closed)) }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }
}
