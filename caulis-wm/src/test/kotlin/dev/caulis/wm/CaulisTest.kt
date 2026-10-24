package dev.caulis.wm

import dev.caulis.core.TestClient
import dev.caulis.core.TestServer
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** `caulis { }` as a user writes it, running nested inside a test server. */
class CaulisTest {

    @Test
    fun `events, float rules and broken user code`() {
        val opened = CopyOnWriteArrayList<String>()
        val focused = CopyOnWriteArrayList<String>()
        withCaulis({
            layout = Dwindle(Gaps(0, 0))
            floatWhen { it.appId == "float-me" }
            onWindowOpened { window -> opened += window.title }
            onFocusChanged { window -> focused += window?.title ?: "-" }
            onWindowOpened { error("a mistake in the user's code") } // must not stop anything
            bind("Super+X") { error("another mistake") }
        }) { caulis, client ->
            val tiled = client.window { it.setTitle("tiled") }
            assertTrue(XdgToplevel.State.TILED_LEFT in tiled.states)
            tiled.draw()
            val floating = client.window { it.setTitle("floating"); it.setAppId("float-me") }
            assertTrue(XdgToplevel.State.TILED_LEFT !in floating.states, "floatWhen made it float")
            floating.draw(200, 100)
            client.waitFor { opened.size == 2 }
            assertEquals(listOf("tiled", "floating"), opened)
            assertEquals(listOf("tiled", "floating"), focused)

            // Pressing the broken binding (Super+X) only logs the mistake.
            caulis.onLoop { running -> running.key(0, 125, true); running.key(0, 45, true) }
            client.connection.roundtrip()
            assertEquals(2, caulis.onLoop { it.desktop.windows.size }, "still running, windows still there")
        }
    }

    // Runs [configure]d Caulis nested in a test server until [test] is done.
    private fun withCaulis(configure: Caulis.() -> Unit, test: (Caulis, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        val socketDirectory = Files.createTempDirectory("caulis-wm-test")
        TestServer(gpu = true).use { outer ->
            val caulis = Caulis().apply {
                configure()
                mode = Caulis.Mode.NESTED
                nestedIn = outer.socketPath
                this.socketDirectory = socketDirectory
                exitAfterSeconds = 30 // never hang the test run
            }
            val started = CompletableFuture<String>()
            caulis.onStart { started.complete(running!!.socket) }
            val thread = Thread.ofPlatform().start { caulis.run() }
            try {
                val socket = started.get(10, TimeUnit.SECONDS)
                TestClient(Connection.connect(socketDirectory.resolve(socket))).use { client -> test(caulis, client) }
            } finally {
                caulis.running?.loop?.post { caulis.quit() }
                thread.join(5000)
                socketDirectory.toFile().deleteRecursively()
            }
        }
    }

    // Runs [action] on the compositor's own thread and returns its result.
    private fun <T> Caulis.onLoop(action: (Running) -> T): T {
        val result = CompletableFuture<T>()
        val running = checkNotNull(running)
        running.loop.post { result.complete(action(running)) }
        return result.get(5, TimeUnit.SECONDS)
    }
}
