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

    @Test
    fun `the log levels from code, and the state written on request`() {
        val lines = CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            withCaulis({
                logLevel = 2
                log("focus", 3)
            }) { caulis, client ->
                assertEquals(2, dev.caulis.log.Log.level)
                assertEquals(3, dev.caulis.log.Log.levelOf("focus"))
                client.window { it.setTitle("logged") }.draw()
                client.waitFor { lines.any { "Opened" in it && "logged" in it } }
                assertTrue(lines.any { " T focus" in it }, "focus in detail")
                assertTrue(lines.none { " T windows" in it }, "the other areas at level 2 only")

                caulis.onLoop { it.key(0, 125, true); it.key(0, 88, true); it.key(0, 88, false); it.key(0, 125, false) } // Super+F12
                client.waitFor { lines.any { "The state now" in it } }
                val state = lines.first { "The state now" in it }
                assertTrue("workspace 1" in state && "logged" in state && "focused" in state, state)
            }
        } finally {
            dev.caulis.log.Log.listener = null
            dev.caulis.log.Log.level = dev.caulis.log.Log.PROBLEMS
            dev.caulis.log.Log.clearLevel("focus")
        }
    }

    // Runs [configure]d Caulis nested in a test server until [test] is done.
    internal fun withCaulis(configure: Caulis.() -> Unit, test: (Caulis, TestClient) -> Unit) {
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
            val started = CompletableFuture<Running>()
            caulis.onStart { started.complete(running!!) }
            val thread = Thread.ofPlatform().start { caulis.run() }
            var running: Running? = null
            // If the compositor's loop stands still for long, what it waits for is printed (for tests that fail rarely).
            val watchdog = Thread.ofPlatform().daemon().start {
                val r = runCatching { started.get(10, TimeUnit.SECONDS) }.getOrNull() ?: return@start
                var reported = 0L
                while (thread.isAlive) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { return@start }
                    val since = r.loop.lastIterationNanos
                    if (System.nanoTime() - since > 2_000_000_000 && since != reported) {
                        reported = since
                        System.err.println("The compositor's loop has stood still for 2 s, at:\n" + thread.stackTrace.joinToString("\n") { "    at $it" })
                    }
                }
            }
            try {
                running = started.get(10, TimeUnit.SECONDS)
                TestClient(Connection.connect(socketDirectory.resolve(running.socket))).use { client -> test(caulis, client) }
            } finally {
                // (The compositor itself: after a reload it runs with another configuration.)
                running?.let { r -> r.loop.post { r.quit() } }
                watchdog.interrupt()
                thread.join(5000)
                socketDirectory.toFile().deleteRecursively()
            }
        }
    }

    // A configuration as [configure] makes it, with the test's own settings (as a reload of
    // the same main() would have them).
    internal fun Caulis.reloaded(configure: Caulis.() -> Unit): Caulis = Caulis().also { next ->
        next.configure()
        next.mode = mode
        next.nestedIn = nestedIn
        next.socketDirectory = socketDirectory
        next.exitAfterSeconds = exitAfterSeconds
    }

    // Runs [action] on the compositor's own thread and returns its result.
    internal fun <T> Caulis.onLoop(action: (Running) -> T): T {
        val result = CompletableFuture<T>()
        val running = checkNotNull(running)
        running.loop.post { result.complete(action(running)) }
        return result.get(5, TimeUnit.SECONDS)
    }
}
