package dev.caulis.core

import dev.caulis.ffi.Device
import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.protocol.client.Connection
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Strange situations the server must survive without hanging or crashing. */
class ResilienceTest {

    @Test
    fun `running out of file descriptors refuses new clients instead of spinning`() {
        TestServer().use { server ->
            val hoarded = ArrayList<Fd>()
            try {
                // Take every free descriptor of the process (the server shares it with us).
                while (true) {
                    hoarded += try {
                        Device.open(Path.of("/dev/null"))
                    } catch (e: NativeException) {
                        break
                    }
                }
                // Free exactly one, for our own end of the connection.
                hoarded.removeLast().close()
                SocketChannel.open(UnixDomainSocketAddress.of(server.socketPath)).use { channel ->
                    // The server cannot keep us; it must close the connection, not ignore it.
                    assertEquals(-1, channel.read(ByteBuffer.allocate(16)), "the server should refuse the connection")
                }
            } finally {
                hoarded.forEach { it.close() }
            }

            // With descriptors free again, programs connect as usual.
            Connection.connect(server.socketPath).use { connection ->
                connection.roundtrip()
                assertTrue(connection.display.isAlive)
            }
        }
    }
}
