package dev.caulis.ffi

import dev.caulis.ffi.c.libc.LibC
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SharedMemoryTest {

    @Test
    fun `written data can be read back`() {
        SharedMemory.create("test", 8192).use { memory ->
            memory.write { it.set(JAVA_LONG, 4096, 123456789L) }
            assertEquals(123456789L, memory.read { it.get(JAVA_LONG, 4096) })
        }
    }

    @Test
    fun `memory sealed with its contents cannot be written by anyone it is sent to`() {
        SharedMemory.sealed("keymap", "xkb_keymap {".toByteArray()).use { memory ->
            assertEquals('x'.code.toByte(), memory.read { it.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0) })
            // A client writes through its copy of the descriptor:
            val failed = runCatching {
                java.nio.channels.FileChannel.open(java.nio.file.Path.of("/proc/self/fd/${memory.fd.value}"), java.nio.file.StandardOpenOption.WRITE).use {
                    it.write(java.nio.ByteBuffer.wrap("TAMPERED".toByteArray()))
                }
            }
            kotlin.test.assertTrue(failed.isFailure, "writing was refused")
            assertEquals('x'.code.toByte(), memory.read { it.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0) })
        }
    }

    @Test
    fun `a file the other process may only read is never mapped for writing`() {
        val file = java.nio.file.Files.createTempFile("readonly", ".bin")
        try {
            java.nio.file.Files.write(file, ByteArray(4096))
            val fd = Arena.ofConfined().use { temp -> Fd.adopt(Libc.open(temp.allocateFrom(file.toString()), 0 or LibC.O_CLOEXEC(), 0)) }
            assertFailsWith<SharedMemoryException> { SharedMemory.map(fd, 4096, writable = true) }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a FIFO sent as shared memory is refused at once, not waited on`() {
        val dir = java.nio.file.Files.createTempDirectory("fifo")
        try {
            val fifo = dir.resolve("f")
            check(ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0) { "mkfifo failed" }
            // (As a hostile client opens it: no writer, without waiting.)
            val raw = java.lang.foreign.Arena.ofConfined().use { temp ->
                Libc.open(temp.allocateFrom(fifo.toString()), LibC.O_NONBLOCK() or LibC.O_CLOEXEC(), 0)
            }
            val result = java.util.concurrent.CompletableFuture<Throwable?>()
            val mapping = Thread {
                result.complete(runCatching { SharedMemory.map(Fd.adopt(raw), 4096).close() }.exceptionOrNull())
            }.apply { isDaemon = true }
            mapping.start()
            // (Before: open() of the FIFO waited for a writer for ever.)
            val error = result.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(error is SharedMemoryException, "refused: $error")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sealed memory cannot be shrunk by anyone`() {
        SharedMemory.create("test", 4096).use { memory ->
            val error = assertFailsWith<NativeException> { Libc.ftruncate(memory.fd.value, 0) }
            assertEquals("ftruncate", error.function)
        }
    }

    @Test
    fun `memory shrunk by its owner gives an exception instead of a crash`() {
        // A "client" creates memory without seals, hands it over, then shrinks it.
        val raw = Arena.ofConfined().use { temp ->
            Libc.memfdCreate(temp.allocateFrom("client"), LibC.MFD_CLOEXEC())
        }
        Libc.ftruncate(raw, 65536)
        val clientCopy = Fd.adopt(raw)
        val received = Fd.adopt(Libc.fcntl(raw, LibC.F_DUPFD_CLOEXEC(), 0))

        SharedMemory.map(received, 65536).use { memory ->
            Libc.ftruncate(clientCopy.value, 0)
            assertFailsWith<SharedMemoryException> {
                memory.read { it.get(JAVA_LONG, 8192) }
            }
        }
        clientCopy.close()
    }

    @Test
    fun `mapping fails if the file is smaller than promised`() {
        val raw = Arena.ofConfined().use { temp ->
            Libc.memfdCreate(temp.allocateFrom("liar"), LibC.MFD_CLOEXEC())
        }
        Libc.ftruncate(raw, 100)
        assertFailsWith<SharedMemoryException> { SharedMemory.map(Fd.adopt(raw), 4096) }
    }

    @Test
    fun `an fd is closed exactly once`() {
        val memory = SharedMemory.create("test", 4096)
        val fd = memory.fd
        memory.close()
        fd.close() // second close does nothing instead of closing someone else's descriptor
        assertFailsWith<IllegalStateException> { fd.value }
    }
}
