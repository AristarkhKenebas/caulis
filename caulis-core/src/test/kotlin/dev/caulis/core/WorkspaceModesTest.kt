package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.tiling.Columns
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The ways workspaces and monitors go together, and workspaces set up as you like. */
class WorkspaceModesTest {

    @Test
    fun `pinned - workspaces are screens and stay on their monitor`() = withServer(WorkspaceMode.PINNED) { server, _ ->
        server.onServer { d ->
            assertEquals(listOf(1, 2), d.monitors.map { it.workspace.number })
            d.switchTo(2)
            assertEquals("TEST-2", d.focusedMonitor.name, "Super+2: the second monitor")
            d.switchTo(3) // new: on the monitor with the focus, and it stays there
            assertEquals(listOf(1, 3), d.monitors.map { it.workspace.number })
            d.focusMonitor(d.monitors[0])
            d.switchTo(2)
            assertEquals(listOf(1, 2), d.monitors.map { it.workspace.number }, "back on its own monitor, not pulled over")
            assertEquals("TEST-2", d.focusedMonitor.name)
            d.workspaceRules = listOf(WorkspaceRule(5).apply { monitor = "TEST-1" })
            d.switchTo(5)
            assertEquals(listOf(5, 2), d.monitors.map { it.workspace.number }, "a rule's monitor wins")
            assertEquals("TEST-1", d.focusedMonitor.name)
        }
    }

    @Test
    fun `separate - every monitor has workspaces of its own`() = withServer(WorkspaceMode.SEPARATE) { server, _ ->
        server.onServer { d ->
            assertEquals(listOf(1, 1), d.monitors.map { it.workspace.number })
            assertNotSame(d.monitors[0].workspace, d.monitors[1].workspace)
            d.switchTo(3)
            assertEquals(listOf(3, 1), d.monitors.map { it.workspace.number }, "only the one with the focus")
        }
    }

    @Test
    fun `primary only - the other monitors are plain screens`() = withServer(WorkspaceMode.PRIMARY_ONLY) { server, client ->
        server.onServer { d -> d.primaryMonitorName = "TEST-2" }
        assertEquals(listOf(0, 1), server.onServer { d -> d.monitors.map { it.workspace.number } }, "workspaces on the primary one")
        client.window().draw()
        client.waitFor { server.onServer { d -> d.allWindows.isNotEmpty() } }
        server.onServer { d ->
            val window = d.allWindows.single()
            assertEquals("TEST-2", d.monitorOf(window).name, "opens on the primary monitor (it got the focus)")
            d.moveToMonitor(window, d.monitors[0])
            assertEquals(0, d.workspaceNumberOf(window), "a screen of its own")
            d.switchTo(4)
            assertEquals(listOf(0, 4), d.monitors.map { it.workspace.number }, "the primary monitor switched, the other stays")
            assertEquals("TEST-2", d.focusedMonitor.name)
            // The primary monitor goes: the other one takes its workspaces (and keeps its window).
            d.removeMonitor(d.monitors[1])
            assertEquals(4, d.monitors.single().workspace.number)
            assertEquals(4, d.workspaceNumberOf(window))
        }
    }

    @Test
    fun `how many, names, persistent ones, back and forth`() = withServer(WorkspaceMode.PER_MONITOR) { server, _ ->
        server.onServer { d ->
            d.workspaceCount = 4
            d.workspaceRules = listOf(
                WorkspaceRule(3).apply { name = "chat"; persistent = true },
                WorkspaceRule(4).apply { layout = Columns() },
            )
            assertTrue(d.allWorkspaces.any { it.number == 3 && it.name == "chat" }, "always there, with its name")
            d.switchTo(7)
            assertEquals(1, d.activeWorkspace.number, "there are only 4")
            d.switchTo(4)
            assertEquals("Columns", d.activeWorkspace.layout.name, "its own layout")
            assertEquals(1, d.previousWorkspaceNumber)
            d.switchTo(d.previousWorkspaceNumber!!)
            assertEquals(1, d.activeWorkspace.number, "back")
            assertEquals(listOf(1, 2, 3), d.workspaceNumbers(), "4 went (empty), 3 stays (persistent)")
            assertEquals(2, d.workspaceAfter(1)) // 2 is shown on the other monitor
            assertEquals(3, d.workspaceAfter(-1), "going round")
            assertEquals(1, d.firstEmptyWorkspace(), "1 has no windows")
        }
    }

    @Test
    fun `one workspace only - switching is off`() = withServer(WorkspaceMode.PER_MONITOR) { server, _ ->
        server.onServer { d ->
            d.workspaceCount = 1
            d.switchTo(3)
            assertNull(d.previousWorkspaceNumber)
            assertSame(d.monitors[0].workspace, d.activeWorkspace)
            assertEquals(1, d.activeWorkspace.number)
        }
    }

    private fun withServer(mode: WorkspaceMode, test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer(monitorCount = 2, workspaceMode = mode).use { server ->
            TestClient(Connection.connect(server.socketPath)).use { test(server, it) }
        }
    }
}
