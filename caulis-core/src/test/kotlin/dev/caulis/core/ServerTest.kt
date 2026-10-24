package dev.caulis.core

import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlShm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    // Object ids the test client picks; the protocol leaves the choice to the client.
    private val display = 1
    private val registry = 2
    private val callback = 3

    // wl_display requests and events
    private val sync = 0
    private val getRegistry = 1
    private val errorEvent = 0
    private val doneEvent = 0

    /** Asks for the registry and waits for the list of globals: name -> (interface, version). */
    private fun RawClient.globals(): Map<String, Pair<Int, Int>> {
        request(display, getRegistry, registry)
        request(display, sync, callback)
        val events = readUntil { it.objectId == callback && it.opcode == doneEvent }
        return events.filter { it.objectId == registry }.associate { event ->
            val name = event.int()
            val iface = event.string()
            val version = event.int()
            iface to (name to version)
        }
    }

    @Test
    fun `a client sees the offered globals`() {
        TestServer().use { server ->
            RawClient(server.socketPath).use { client ->
                val globals = client.globals()
                assertEquals(Compositor.VERSION, globals.getValue("wl_compositor").second)
                assertEquals(Shm.VERSION, globals.getValue("wl_shm").second)
                assertEquals(Output.VERSION, globals.getValue("wl_output").second)
            }
        }
    }

    @Test
    fun `using an unknown object disconnects only that client`() {
        TestServer().use { server ->
            RawClient(server.socketPath).use { bad ->
                bad.request(42, 0)
                val events = bad.readUntil { it.objectId == display && it.opcode == errorEvent }
                val error = events.last()
                assertEquals(display, error.int()) // the error is about wl_display itself
                assertEquals(WlDisplay.Error.INVALID_OBJECT, error.int())
                assertTrue(error.string().contains("42"))
            }
            // The server keeps serving everybody else.
            RawClient(server.socketPath).use { good -> assertTrue(good.globals().isNotEmpty()) }
        }
    }

    @Test
    fun `a buffer outside its shared memory is refused`() {
        TestServer().use { server ->
            RawClient(server.socketPath).use { client ->
                val shmName = client.globals().getValue("wl_shm").first
                val shm = 10
                val pool = 11
                val buffer = 12
                client.request(registry, 0, shmName, "wl_shm", 1, shm) // wl_registry.bind
                SharedMemory.create("pool", 4096).use { memory ->
                    client.request(shm, 0, pool, 4096, fds = listOf(memory.fd)) // create_pool(id, fd, size)
                }
                // 100 rows of 400 bytes do not fit into 4096 bytes.
                client.request(pool, 0, buffer, 0, 100, 100, 400, WlShm.Format.ARGB8888)
                val error = client.readUntil { it.objectId == display && it.opcode == errorEvent }.last()
                assertEquals(pool, error.int(), "the error is reported on the pool")
                assertEquals(WlShm.Error.INVALID_STRIDE, error.int())
            }
        }
    }

    @Test
    fun `a malformed message disconnects the client`() {
        TestServer().use { server ->
            RawClient(server.socketPath).use { client ->
                client.request(display, sync, callback) // fine
                client.request(display, getRegistry)     // missing its argument
                val error = client.readUntil { it.objectId == display && it.opcode == errorEvent }.last()
                error.int()
                assertEquals(WlDisplay.Error.INVALID_METHOD, error.int())
            }
        }
    }
}
