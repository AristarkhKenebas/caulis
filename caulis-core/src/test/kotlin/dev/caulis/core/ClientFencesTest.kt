package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.Connection
import dev.caulis.render.Color
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pictures waiting for a program's GPU: shown only once it is done, in order. (A pipe stands in
 * for the kernel's fence, and shared memory for a GPU buffer: the waiting is the same; the ioctl
 * itself needs a real GPU buffer and is only run on real hardware.)
 */
class ClientFencesTest {

    private fun pipe(): Pair<Fd, Fd> {
        val (read, write) = Fd.pipe()
        return read to write
    }

    private fun color(client: TestClient, window: TestClient.TestWindow, argb: Int) {
        val w = window.width.takeIf { it > 0 } ?: 100
        val h = window.height.takeIf { it > 0 } ?: 100
        val (buffer, memory) = client.buffer(w, h)
        memory.write { s -> for (i in 0 until w * h) s.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), argb) }
        window.surface.attach(buffer, 0, 0)
        window.surface.damageBuffer(0, 0, w, h)
        window.surface.commit()
    }

    private fun waitForColor(server: ScreenTestServer, client: TestClient, argb: Int) {
        client.waitFor { argb in server.screenshot().asList() }
    }

    @Test
    fun `a picture waits for its fence, and the next waits for it`() {
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                val fences = ConcurrentLinkedQueue<Fd>()
                server.onServer { d ->
                    d.backgroundChangeMillis = 0
                    d.background = Background.gradient(Color.hex("#102030"), Color.hex("#f0e0d0"), degrees = 30.0)
                }
                val window = client.window()
                color(client, window, RED)
                client.waitFor { server.onServer { it.windows.size } == 1 }
                client.connection.roundtrip()
                color(client, window, RED)
                client.connection.roundtrip()
                waitForColor(server, client, RED)
                // From now on each picture waits for a pipe the test writes to.
                val writers = ConcurrentLinkedQueue<Fd>()
                server.onServer { d ->
                    d.compositor.clientFences = { _ ->
                        val (read, write) = pipe()
                        writers += write
                        fences += read
                        listOf(read)
                    }
                }
                color(client, window, GREEN)
                color(client, window, BLUE)
                client.connection.roundtrip()
                Thread.sleep(150)
                val shot = server.screenshot().asList()
                assertTrue(RED in shot && GREEN !in shot && BLUE !in shot, "the picture before stays")
                assertEquals(2, writers.size)
                // The second one's work done first: still waits for the first in line.
                val first = writers.poll()
                val second = writers.poll()
                second.close()
                Thread.sleep(100)
                val still = server.screenshot().asList()
                assertTrue(RED in still && BLUE !in still, "in order")
                first.close()
                waitForColor(server, client, BLUE)
                // No fence: at once, as without this.
                server.onServer { d -> d.compositor.clientFences = null }
                color(client, window, GREEN)
                waitForColor(server, client, GREEN)
                server.onServer { d -> assertTrue(d.windows.isNotEmpty()) }
                assertEquals(false, pipe().first.readableNow())
            }
        }
    }

    @Test
    fun `a program gone while a picture waits leaves nothing behind`() {
        ScreenTestServer().use { server ->
            val write = java.util.concurrent.atomic.AtomicReference<Fd>()
            TestClient(Connection.connect(server.socketPath)).use { client ->
                val window = client.window()
                color(client, window, RED)
                client.waitFor { server.onServer { it.windows.size } == 1 }
                server.onServer { d ->
                    d.compositor.clientFences = { _ ->
                        val (read, w) = pipe()
                        write.set(w)
                        listOf(read)
                    }
                }
                color(client, window, GREEN)
                client.connection.roundtrip()
            }
            server.onServer { d -> d.compositor.clientFences = null }
            Thread.sleep(200)
            write.get().close()
            Thread.sleep(100)
            // Still alive, and no window left.
            assertEquals(0, server.onServer { it.windows.size })
        }
    }

    private companion object {
        const val RED = 0xffff0000.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val BLUE = 0xff0000ff.toInt()
    }
}
