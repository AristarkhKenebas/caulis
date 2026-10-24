package dev.caulis.ffi

import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertEquals

/** Starting programs with our descriptors handed over (as Xwayland needs). */
class ChildProcessTest {

    @Test
    fun `descriptors handed over to each other's numbers all arrive`() {
        // Our descriptors get numbers the other one should end up with (as happens by chance).
        val (a1, a2) = UnixSocket.pair()
        val (b1, b2) = UnixSocket.pair()
        a1.use { b1.use {
            val child = a2.use { b2.use {
                ChildProcess.start("sh", listOf("-c", "printf A >&${b2.fd.value}; printf B >&${a2.fd.value}"), System.getenv(),
                    // a2 goes where b2 is now and the other way round.
                    mapOf(b2.fd.value to a2.fd, a2.fd.value to b2.fd))
            } }
            child.use {
                repeat(200) { if (child.reap() == null) Thread.sleep(10) }
                assertEquals(0, child.reap())
                Arena.ofConfined().use { arena ->
                    val buffer = arena.allocate(4)
                    assertEquals("A", buffer.asSlice(0, Libc.read(a1.fd.value, buffer)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE).decodeToString())
                    assertEquals("B", buffer.asSlice(0, Libc.read(b1.fd.value, buffer)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE).decodeToString())
                }
            }
        } }
    }

    @Test
    fun `a descriptor handed over as 3 reaches the program, and its exit status comes back`() {
        val (ours, theirs) = UnixSocket.pair()
        ours.use {
            val child = theirs.use {
                ChildProcess.start("sh", listOf("-c", "printf hello >&3; exit 7"), System.getenv(), mapOf(3 to theirs.fd))
            }
            child.use {
                // Wait for the program to end (its pidfd turns readable), then read what it wrote.
                var status: Int? = null
                repeat(200) { if (status == null) { status = child.reap(); if (status == null) Thread.sleep(10) } }
                assertEquals(7, status)
                Arena.ofConfined().use { arena ->
                    val buffer = arena.allocate(16)
                    val read = Libc.read(ours.fd.value, buffer)
                    assertEquals("hello", buffer.asSlice(0, read).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE).decodeToString())
                }
            }
        }
    }
}
