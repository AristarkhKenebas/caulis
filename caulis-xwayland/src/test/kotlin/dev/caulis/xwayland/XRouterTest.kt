package dev.caulis.xwayland

import dev.caulis.ffi.Fd
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The X display's door: what goes in comes out on the other side, bytes and descriptors, both ways. */
class XRouterTest {

    private val dir: Path = Files.createTempDirectory("caulis-router")
    private val door = UnixListener.listen(dir.resolve("door"))
    private val server = UnixListener.listen(dir.resolve("server"))
    private val routed = ArrayList<UnixSocket.Credentials>()
    private val router = XRouter(door) { who -> synchronized(routed) { routed += who }; server.path }

    @AfterTest
    fun cleanUp() {
        router.close()
        door.close()
        server.close()
        dir.toFile().deleteRecursively()
    }

    // A connection through the door, and the one the "Xwayland" side accepts for it.
    private fun connect(): Pair<UnixSocket, UnixSocket> {
        val program = UnixSocket.connect(door.path)
        val deadline = System.nanoTime() + 5_000_000_000
        while (true) {
            server.accept()?.let { return program to it }
            check(System.nanoTime() < deadline) { "not joined" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `bytes go through both ways, and the program is known by its process`() {
        val (program, xwayland) = connect()
        program.use {
            xwayland.use {
                send(program, "hello X".toByteArray())
                assertEquals("hello X", receive(xwayland, 7).decodeToString())
                send(xwayland, "hello program".toByteArray())
                assertEquals("hello program", receive(program, 13).decodeToString())
            }
        }
        assertEquals(ProcessHandle.current().pid().toInt(), synchronized(routed) { routed.single().pid })
    }

    @Test
    fun `file descriptors go along with their bytes`() {
        val (program, xwayland) = connect()
        program.use {
            xwayland.use {
                // As DRI3 or MIT-SHM do: a descriptor with a request; this one, a pipe's end.
                val (readEnd, writeEnd) = Fd.pipe()
                Arena.ofConfined().use { arena ->
                    assertEquals(3L, program.send(arena.allocateFrom(JAVA_BYTE, *"fd!".toByteArray()), listOf(writeEnd)))
                }
                writeEnd.close()
                val fds = ArrayList<Fd>()
                val data = receive(xwayland, 3, fds)
                assertEquals("fd!", data.decodeToString())
                assertEquals(1, fds.size, "the descriptor came along")
                // It is really the pipe: what is written into it comes out of our end.
                Arena.ofConfined().use { arena -> fds.single().write(arena.allocateFrom(JAVA_BYTE, *"through".toByteArray())) }
                fds.single().close()
                readEnd.use { assertEquals("through", Arena.ofConfined().use { arena ->
                    val buffer = arena.allocate(16)
                    val n = it.read(buffer)
                    buffer.asSlice(0, n).toArray(JAVA_BYTE).decodeToString()
                }) }
            }
        }
    }

    @Test
    fun `much data both ways at once, a slow side making the other wait`() {
        val (program, xwayland) = connect()
        program.use {
            xwayland.use {
                val up = Random(1).nextBytes(20_000_000)
                val down = Random(2).nextBytes(20_000_000)
                // Each side writes everything while reading what comes (as X does); one reads slowly.
                val upRead = CompletableFuture.supplyAsync { pump(xwayland, down, up.size, slow = true) }
                val downRead = CompletableFuture.supplyAsync { pump(program, up, down.size, slow = false) }
                assertContentEquals(digest(up), digest(upRead.get(60, TimeUnit.SECONDS)), "the requests")
                assertContentEquals(digest(down), digest(downRead.get(60, TimeUnit.SECONDS)), "the replies")
            }
        }
    }

    @Test
    fun `a side that closes closes the other`() {
        val (program, xwayland) = connect()
        xwayland.use {
            program.close()
            assertEquals(0, receive(xwayland, 1, expectEnd = true).size, "the Xwayland side sees the end")
        }
    }

    // Writes [out] into [socket] while reading [expected] bytes from it; returns what was read.
    private fun pump(socket: UnixSocket, out: ByteArray, expected: Int, slow: Boolean): ByteArray = Arena.ofConfined().use { arena ->
        val read = java.io.ByteArrayOutputStream()
        val outgoing = arena.allocate(out.size.toLong()).also { MemorySegment.copy(out, 0, it, JAVA_BYTE, 0, out.size) }
        val incoming = arena.allocate(65536)
        var sent = 0L
        val fds = ArrayList<Fd>()
        val deadline = System.nanoTime() + 60_000_000_000
        while (sent < out.size || read.size() < expected) {
            check(System.nanoTime() < deadline) { "stuck: sent $sent, read ${read.size()}" }
            if (sent < out.size) {
                val n = socket.send(outgoing.asSlice(sent, minOf(65536L, out.size - sent)))
                if (n > 0) sent += n
            }
            val n = socket.receive(if (slow) incoming.asSlice(0, 4096) else incoming, fds)
            if (n > 0) read.write(incoming.asSlice(0, n).toArray(JAVA_BYTE))
            if (n <= 0 && sent >= out.size) Thread.sleep(1)
        }
        read.toByteArray()
    }

    private fun send(socket: UnixSocket, bytes: ByteArray) = Arena.ofConfined().use { arena ->
        val data = arena.allocate(bytes.size.toLong()).also { MemorySegment.copy(bytes, 0, it, JAVA_BYTE, 0, bytes.size) }
        check(socket.send(data) == bytes.size.toLong())
    }

    private fun receive(socket: UnixSocket, count: Int, fds: MutableList<Fd> = ArrayList(), expectEnd: Boolean = false): ByteArray =
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(count.toLong().coerceAtLeast(1))
            val result = java.io.ByteArrayOutputStream()
            val deadline = System.nanoTime() + 5_000_000_000
            while (result.size() < count || expectEnd) {
                check(System.nanoTime() < deadline) { "nothing came" }
                val n = socket.receive(buffer, fds)
                if (n == 0L) return@use result.toByteArray() // closed
                if (n > 0) result.write(buffer.asSlice(0, n).toArray(JAVA_BYTE)) else Thread.sleep(2)
            }
            result.toByteArray()
        }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
