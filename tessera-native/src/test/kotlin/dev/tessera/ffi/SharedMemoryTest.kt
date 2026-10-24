package dev.tessera.ffi

import dev.tessera.ffi.c.libc.LibC
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedMemoryTest {

    @Test
    fun `written data can be read back`() {
        SharedMemory.create("test", 8192).use { memory ->
            memory.write { it.set(JAVA_LONG, 4096, 123456789L) }
            assertEquals(123456789L, memory.read { it.get(JAVA_LONG, 4096) })
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
