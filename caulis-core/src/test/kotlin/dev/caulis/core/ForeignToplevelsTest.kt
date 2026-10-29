package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.extforeigntoplevellistv1.ExtForeignToplevelHandleV1
import dev.caulis.protocol.client.extforeigntoplevellistv1.ExtForeignToplevelListV1
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wlrforeigntoplevelmanagementunstablev1.ZwlrForeignToplevelHandleV1
import dev.caulis.protocol.client.wlrforeigntoplevelmanagementunstablev1.ZwlrForeignToplevelManagerV1
import dev.caulis.tiling.Floating
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Docks and taskbars: they see every window and can act on it (wlr-foreign-toplevel, ext-foreign-toplevel-list). */
class ForeignToplevelsTest {

    // What a dock knows about one window (as of the last "done").
    private class Entry(val handle: ZwlrForeignToplevelHandleV1) {
        @Volatile var title = ""
        @Volatile var appId = ""
        @Volatile var states: Set<Int> = emptySet()
        @Volatile var closed = false
        @Volatile var outputs = 0
        var pendingTitle = ""
        var pendingAppId = ""
        var pendingStates: Set<Int> = emptySet()
    }

    // A dock: a program of its own, keeping the window list up to date. (Its waits only pump its
    // own connection: what the apps send must be flushed with apps.connection.roundtrip().)
    private class Dock(val client: TestClient) {
        val entries = CopyOnWriteArrayList<Entry>()

        init {
            var found = -1
            val registry = client.connection.display.getRegistry()
            registry.events = object : WlRegistry.Events {
                override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                    if (`interface` == "zwlr_foreign_toplevel_manager_v1") found = name
                }
            }
            client.connection.roundtrip()
            registry.bind(found, ZwlrForeignToplevelManagerV1, 3).events = object : ZwlrForeignToplevelManagerV1.Events {
                override fun toplevel(proxy: ZwlrForeignToplevelManagerV1, toplevel: ZwlrForeignToplevelHandleV1) {
                    val entry = Entry(toplevel)
                    entries += entry
                    toplevel.events = object : ZwlrForeignToplevelHandleV1.Events {
                        override fun title(proxy: ZwlrForeignToplevelHandleV1, title: String) { entry.pendingTitle = title }
                        override fun appId(proxy: ZwlrForeignToplevelHandleV1, appId: String) { entry.pendingAppId = appId }
                        override fun outputEnter(proxy: ZwlrForeignToplevelHandleV1, output: dev.caulis.protocol.client.wayland.WlOutput) { entry.outputs++ }
                        override fun state(proxy: ZwlrForeignToplevelHandleV1, state: ByteArray) {
                            val ints = ByteBuffer.wrap(state).order(ByteOrder.nativeOrder()).asIntBuffer()
                            entry.pendingStates = (0 until ints.remaining()).map { ints.get(it) }.toSet()
                        }
                        override fun done(proxy: ZwlrForeignToplevelHandleV1) {
                            entry.title = entry.pendingTitle
                            entry.appId = entry.pendingAppId
                            entry.states = entry.pendingStates
                        }
                        override fun closed(proxy: ZwlrForeignToplevelHandleV1) { entry.closed = true }
                    }
                }
            }
            client.connection.roundtrip()
        }

        fun entry(title: String) = entries.first { it.title == title && !it.closed }
    }

    @Test
    fun `a dock sees every window, its title and state, and when it closes`() = withServer { server, apps, dockClient ->
        apps.output // the dock binds the output so it hears which monitor the windows are on
        dockClient.output
        val dock = Dock(dockClient)
        val first = apps.window { it.setTitle("one"); it.setAppId("org.example.One") }
        first.draw()
        apps.connection.roundtrip()
        dockClient.waitFor { dock.entries.any { it.title == "one" } }
        val one = dock.entry("one")
        assertEquals("org.example.One", one.appId)
        dockClient.waitFor { one.states == setOf(ACTIVATED) }
        assertTrue(one.outputs >= 1, "on the monitor")

        val second = apps.window { it.setTitle("two") }
        second.draw()
        apps.connection.roundtrip()
        dockClient.waitFor { dock.entries.any { it.title == "two" && it.states == setOf(ACTIVATED) } }
        dockClient.waitFor { one.states.isEmpty() }

        first.toplevel.setTitle("one, renamed")
        apps.connection.roundtrip()
        dockClient.waitFor { one.title == "one, renamed" }

        second.toplevel.destroy()
        apps.connection.roundtrip()
        dockClient.waitFor { dock.entries.first { it.title == "two" }.closed }
        assertEquals(1, server.onServer { it.allWindows.size })
    }

    @Test
    fun `a dock minimizes, restores and maximizes windows`() = withServer { server, apps, dockClient ->
        val dock = Dock(dockClient)
        apps.window { it.setTitle("app") }.draw()
        apps.connection.roundtrip()
        dockClient.waitFor { dock.entries.any { it.title == "app" } }
        val app = dock.entry("app")

        app.handle.setMinimized()
        dockClient.waitFor { MINIMIZED in app.states }
        assertTrue(server.onServer { d -> d.isMinimized(d.allWindows.first()) })

        app.handle.activate(dockClient.seat)
        dockClient.waitFor { app.states == setOf(ACTIVATED) }
        assertTrue(server.onServer { d -> !d.isMinimized(d.allWindows.first()) && d.focusedWindow != null })

        app.handle.setMaximized()
        dockClient.waitFor { MAXIMIZED in app.states }
    }

    @Test
    fun `a minimized window flies into its dock icon`() = withServer { server, apps, dockClient ->
        server.onServer {
            it.layout = Floating
            it.animationMillis = 2000 // slow, to look at it on the way
            it.animations = Animations.ON // (off by default)
        }
        val dock = Dock(dockClient)
        // The dock is a window here too; its "icon" is at 10, 10 inside it.
        val dockWindow = dockClient.window { it.setTitle("dock") }
        dockWindow.draw(100, 60)
        dockClient.connection.roundtrip()
        apps.window { it.setTitle("app") }.draw(300, 200)
        apps.connection.roundtrip()
        dockClient.waitFor { dock.entries.any { it.title == "app" } }
        // Both placed and drawn where they stay (under load the app may still be opening).
        val (dockRect, appRect) = eventually({ "both windows placed and drawn" }) {
            server.onServer { d ->
                val dockW = d.allWindows.firstOrNull { it.title == "dock" }
                val appW = d.allWindows.firstOrNull { it.title == "app" }
                val dr = dockW?.let(d::screenRect)
                val ar = appW?.let(d::screenRect)
                if (dr != null && ar != null && d.shownRect(appW) == ar) dr to ar else null
            }
        }
        val icon = Rect(dockRect.x + 10, dockRect.y + 10, 32, 32)

        dock.entry("app").handle.setRectangle(dockWindow.surface, 10, 10, 32, 32)
        dock.entry("app").handle.setMinimized()
        dockClient.waitFor { MINIMIZED in dock.entry("app").states }
        // On the way (looked at until it is, however slow the frames come): smaller than it was,
        // bigger than the icon, and closer to the icon than it started.
        var last: Rect? = null
        val onTheWay = eventually({ "on its way into the icon $icon from $appRect (last seen: $last)" }) {
            val now = server.onServer { d -> d.allWindows.firstOrNull { it.title == "app" }?.let(d::shownRect) }
            last = now
            now?.takeIf { r ->
                r.width in (icon.width + 1) until appRect.width &&
                    (Math.abs(r.x - icon.x) < Math.abs(appRect.x - icon.x) || Math.abs(r.y - icon.y) < Math.abs(appRect.y - icon.y))
            }
        }
        assertTrue(onTheWay.width < appRect.width)
    }

    // What [probe] gives once it gives something (tried every 20 ms, for up to 10 s).
    private fun <T : Any> eventually(what: () -> String, probe: () -> T?): T {
        val until = System.nanoTime() + 10_000_000_000L
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > until) throw AssertionError("Not reached in 10 s: ${what()}")
            Thread.sleep(20)
        }
    }

    @Test
    fun `the read-only list has every window with a lasting identifier`() = withServer { _, apps, dockClient ->
        val titles = CopyOnWriteArrayList<String>()
        val identifiers = CopyOnWriteArrayList<String>()
        var found = -1
        val registry = dockClient.connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                if (`interface` == "ext_foreign_toplevel_list_v1") found = name
            }
        }
        dockClient.connection.roundtrip()
        registry.bind(found, ExtForeignToplevelListV1, 1).events = object : ExtForeignToplevelListV1.Events {
            override fun toplevel(proxy: ExtForeignToplevelListV1, toplevel: ExtForeignToplevelHandleV1) {
                toplevel.events = object : ExtForeignToplevelHandleV1.Events {
                    override fun identifier(proxy: ExtForeignToplevelHandleV1, identifier: String) { identifiers += identifier }
                    override fun title(proxy: ExtForeignToplevelHandleV1, title: String) { titles += title }
                }
            }
        }
        apps.window { it.setTitle("a") }.draw()
        apps.connection.roundtrip()
        apps.window { it.setTitle("b") }.draw()
        apps.connection.roundtrip()
        dockClient.waitFor { titles.containsAll(listOf("a", "b")) }
        assertEquals(2, identifiers.toSet().size, "a different identifier for each")
    }

    private fun withServer(test: (ScreenTestServer, TestClient, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { apps ->
                TestClient(Connection.connect(server.socketPath)).use { dock -> test(server, apps, dock) }
            }
        }
    }

    private companion object {
        const val MAXIMIZED = 0
        const val MINIMIZED = 1
        const val ACTIVATED = 2
    }
}
