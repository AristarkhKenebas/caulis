package dev.caulis.ffi

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpollAndSocketTest {

    @Test
    fun `epoll wakes up when a socket has data`() {
        val (left, right) = UnixSocket.pair()
        left.use { right.use {
            Epoll().use { epoll ->
                epoll.add(right.fd, read = true)
                assertEquals(0, epoll.wait(timeoutMillis = 0) { _, _ -> }, "nothing to read yet")

                Arena.ofConfined().use { arena -> left.send(arena.allocateFrom("hi")) }

                var readable = false
                val count = epoll.wait(timeoutMillis = 1000) { fd, ready ->
                    assertEquals(right.fd.value, fd)
                    readable = ready.readable
                }
                assertEquals(1, count)
                assertTrue(readable)
            }
        } }
    }

    @Test
    fun `bytes and a file descriptor travel through a socket pair`() {
        val (left, right) = UnixSocket.pair()
        left.use { right.use {
            Arena.ofConfined().use { arena ->
                SharedMemory.create("test", 4096).use { memory ->
                    memory.write { it.set(JAVA_BYTE, 0, 42) }
                    assertEquals(5, left.send(arena.allocateFrom("data"), listOf(memory.fd)))
                }

                val buffer = arena.allocate(64)
                val fds = mutableListOf<Fd>()
                assertEquals(5, right.receive(buffer, fds))
                assertEquals("data", buffer.getString(0))
                assertEquals(1, fds.size)

                // The received descriptor points to the same memory, even though the
                // sender already closed its copy.
                SharedMemory.map(fds.single(), 4096).use { received ->
                    assertEquals(42, received.read { it.get(JAVA_BYTE, 0) }.toInt())
                }
            }
        } }
    }

    @Test
    fun `receive returns -1 when nothing is waiting and 0 when the peer is gone`() {
        val (left, right) = UnixSocket.pair()
        right.use {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(16)
                assertEquals(-1, right.receive(buffer, mutableListOf()))
                left.close()
                assertEquals(0, right.receive(buffer, mutableListOf()))
            }
        }
    }

    @Test
    fun `a listener accepts a connection and knows who connected`() {
        val dir = Files.createTempDirectory("caulis-test")
        val path = dir.resolve("socket")
        UnixListener.listen(path).use { listener ->
            assertTrue(Files.exists(path))
            UnixSocket.connect(path).use {
                val accepted = assertNotNull(listener.accept())
                accepted.use {
                    assertEquals(ProcessHandle.current().pid().toInt(), it.peerCredentials().pid)
                }
            }
            assertEquals(null, listener.accept(), "no second connection is waiting")
        }
        assertFalse(Files.exists(path), "closing the listener removes the socket file")
        Files.delete(dir)
    }
}
