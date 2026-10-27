package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockSurfaceV1
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.render.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Screen lockers (ext-session-lock): nothing of the desktop shows, only the locker gets input. */
class SessionLockTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    // A locker's lock as seen by the test: whether it heard "locked" or "finished".
    private class Locker(val lock: ExtSessionLockV1) {
        @Volatile var locked = false
        @Volatile var finished = false
    }

    private fun TestClient.lock(): Locker {
        val lock = sessionLock!!.lock()
        val locker = Locker(lock)
        lock.events = object : ExtSessionLockV1.Events {
            override fun locked(proxy: ExtSessionLockV1) {
                locker.locked = true
            }

            override fun finished(proxy: ExtSessionLockV1) {
                locker.finished = true
            }
        }
        return locker
    }

    // A lock surface; answers configures (unless [acknowledge] is false) and remembers the size.
    private class Picture(val surface: WlSurface) {
        var width = 0
        var height = 0
    }

    private fun TestClient.lockSurface(locker: Locker, acknowledge: Boolean = true): Picture {
        val surface = compositor.createSurface()
        val picture = Picture(surface)
        locker.lock.getLockSurface(surface, output!!).events = object : ExtSessionLockSurfaceV1.Events {
            override fun configure(proxy: ExtSessionLockSurfaceV1, serial: Int, width: Int, height: Int) {
                picture.width = width
                picture.height = height
                if (acknowledge) proxy.ackConfigure(serial)
            }
        }
        connection.roundtrip()
        return picture
    }

    @Test
    fun `locking hides everything at once and only the locker gets the keyboard`() = withServer { server, client ->
        server.onServer { it.lockColor = Color(1f, 0f, 0f) }
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.keyboardFocus?.roleObject is Window } }
        val corner = server.screenshot().at(width - 1, height - 1)

        val locker = client.lock()
        client.waitFor { locker.locked }
        assertTrue(server.onServer { it.locked })
        // "locked" only comes once the screen shows nothing of the desktop.
        // Everything but the cursor (from whatever cursor theme is installed) is the lock colour.
        val shot = server.screenshot()
        val (px, py) = server.onServer { it.pointerX.toInt() to it.pointerY.toInt() }
        val other = shot.indices.filter { shot[it] != RED && (it % width - px !in -64..64 || it / width - py !in -64..64) }
        assertTrue(other.isEmpty(), "only the lock colour on screen, but ${other.size} pixels differ")
        // Even asking for it, a window cannot get the keyboard now.
        server.onServer { d -> d.focus(d.windows.first()) }
        assertEquals(null, server.onServer { it.keyboardFocus })

        val picture = client.lockSurface(locker)
        assertEquals(width to height, picture.width to picture.height, "as big as the screen")
        client.draw(picture.surface, picture.width, picture.height)
        client.waitFor { server.onServer { d -> d.keyboardFocus?.roleObject is LockSurface } }
        assertEquals(BLACK, server.screenshot().at(width - 1, height - 1), "the locker's picture")

        locker.lock.unlockAndDestroy()
        client.waitFor { !server.onServer { it.locked } }
        assertTrue(server.onServer { d -> d.keyboardFocus?.roleObject is Window }, "the window has the keyboard again")
        client.waitFor { server.screenshot().at(width - 1, height - 1) == corner }
    }

    @Test
    fun `a crashed locker leaves the session locked until a new one takes over`() = withServer { server, client ->
        val crashing = TestClient(Connection.connect(server.socketPath))
        val first = crashing.lock()
        crashing.waitFor { first.locked }
        crashing.close() // the locker crashes

        client.waitFor { server.screenshot()[0] == ABANDONED }
        assertTrue(server.onServer { it.locked }, "still locked")

        val second = client.lock()
        client.waitFor { second.locked }
        // While the new locker is alive, nobody else can take over.
        TestClient(Connection.connect(server.socketPath)).use { other ->
            val third = other.lock()
            other.waitFor { third.finished }
            assertFalse(third.locked)
        }
        second.lock.unlockAndDestroy()
        client.waitFor { !server.onServer { it.locked } }
    }

    @Test
    fun `unlocking before being locked is a protocol error`() = withServer { server, client ->
        val locker = client.lock()
        locker.lock.unlockAndDestroy() // in the same batch: "locked" cannot have arrived yet
        val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        assertTrue(error.message!!.contains("never locked"), error.message)
        assertTrue(server.onServer { it.locked }, "a misbehaving locker does not unlock the session")
    }

    @Test
    fun `a picture before acknowledging the size is a protocol error`() = withServer { _, client ->
        val locker = client.lock()
        val picture = client.lockSurface(locker, acknowledge = false)
        client.draw(picture.surface, picture.width, picture.height)
        val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        assertTrue(error.message!!.contains("acknowledged"), error.message)
    }

    @Test
    fun `a picture of the wrong size is a protocol error`() = withServer { _, client ->
        val locker = client.lock()
        val picture = client.lockSurface(locker)
        client.draw(picture.surface, 100, 100)
        val error = assertFailsWith<ConnectionException> { client.connection.roundtrip() }
        assertTrue(error.message!!.contains("not ${width}x$height"), error.message)
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    @Test
    fun `the locker on the second monitor gets the pointer there`() {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer(monitorCount = 2).use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                val locker = client.lock()
                client.waitFor { locker.locked }
                var entered: WlSurface? = null
                client.seat.getPointer().events = object : dev.caulis.protocol.client.wayland.WlPointer.Events {
                    override fun enter(proxy: dev.caulis.protocol.client.wayland.WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
                        entered = surface
                    }
                }
                // One lock surface on each monitor (the second at x = WIDTH).
                val pictures = client.outputs.map { output ->
                    val surface = client.compositor.createSurface()
                    val picture = Picture(surface)
                    locker.lock.getLockSurface(surface, output).events = object : ExtSessionLockSurfaceV1.Events {
                        override fun configure(proxy: ExtSessionLockSurfaceV1, serial: Int, width: Int, height: Int) {
                            picture.width = width
                            picture.height = height
                            proxy.ackConfigure(serial)
                        }
                    }
                    client.connection.roundtrip()
                    client.draw(surface, picture.width, picture.height)
                    picture
                }
                client.connection.roundtrip()
                server.onServer { d -> d.pointerMoved(0, width + 10.0, 10.0) }
                // (It was over the first monitor's before.)
                client.waitFor { entered === pictures[1].surface }
            }
        }
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLACK = 0xFF000000.toInt()
        // Desktop.abandonedLockColor (0.35, 0.05, 0.05) in 8 bits.
        const val ABANDONED = 0xFF590D0D.toInt()
    }
}
