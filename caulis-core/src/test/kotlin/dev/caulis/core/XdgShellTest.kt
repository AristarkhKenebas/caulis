package dev.caulis.core

import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ConnectionException
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlShm
import dev.caulis.protocol.client.xdgshell.XdgSurface
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import dev.caulis.protocol.client.xdgshell.XdgWmBase
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The window handshake of xdg-shell, as a real client does it. */
class XdgShellTest {

    private class Globals(connection: Connection) {
        val registry = connection.display.getRegistry()
        private val names = HashMap<String, Int>()

        init {
            registry.events = object : WlRegistry.Events {
                override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                    names[`interface`] = name
                }
            }
            connection.roundtrip()
        }

        val compositor = registry.bind(names.getValue("wl_compositor"), WlCompositor, 6)
        val shm = registry.bind(names.getValue("wl_shm"), WlShm, 1)
        val wmBase = registry.bind(names.getValue("xdg_wm_base"), XdgWmBase, 6)
    }

    @Test
    fun `configure, acknowledge, draw - then the window is shown`() {
        TestServer().use { server ->
            Connection.connect(server.socketPath).use { connection ->
                val globals = Globals(connection)
                val surface = globals.compositor.createSurface()
                val xdgSurface = globals.wmBase.getXdgSurface(surface)
                val toplevel = xdgSurface.getToplevel()
                toplevel.setTitle("test window")

                var configureSerial = -1
                var toplevelConfigured = false
                toplevel.events = object : XdgToplevel.Events {
                    override fun configure(proxy: XdgToplevel, width: Int, height: Int, states: ByteArray) {
                        toplevelConfigured = true
                    }
                }
                xdgSurface.events = object : XdgSurface.Events {
                    override fun configure(proxy: XdgSurface, serial: Int) {
                        configureSerial = serial
                    }
                }

                surface.commit() // empty: asks for the first configure
                connection.roundtrip()
                assertTrue(toplevelConfigured, "the server sent a toplevel configure")
                assertTrue(configureSerial > 0, "and finished it with xdg_surface.configure")
                assertTrue(server.mappedTitles.isEmpty(), "not shown before it has a picture")

                xdgSurface.ackConfigure(configureSerial)
                SharedMemory.create("picture", 100L * 50 * 4).use { memory ->
                    memory.write { pixels -> for (i in 0 until 100 * 50) pixels.setAtIndex(JAVA_INT, i.toLong(), 0x336699) }
                    val pool = globals.shm.createPool(memory.fd, 100 * 50 * 4)
                    val buffer = pool.createBuffer(0, 100, 50, 400, WlShm.Format.XRGB8888)
                    surface.attach(buffer, 0, 0)
                    surface.damageBuffer(0, 0, 100, 50)
                    surface.commit()
                    connection.roundtrip()
                }
                assertEquals(listOf("test window"), server.mappedTitles)
            }
        }
    }

    @Test
    fun `a picture before acknowledging the configure is a protocol error`() {
        TestServer().use { server ->
            Connection.connect(server.socketPath).use { connection ->
                val globals = Globals(connection)
                val surface = globals.compositor.createSurface()
                val xdgSurface = globals.wmBase.getXdgSurface(surface)
                xdgSurface.getToplevel()
                surface.commit()
                connection.roundtrip()

                SharedMemory.create("picture", 4096).use { memory ->
                    val pool = globals.shm.createPool(memory.fd, 4096)
                    surface.attach(pool.createBuffer(0, 16, 16, 64, WlShm.Format.XRGB8888), 0, 0)
                    surface.commit() // no ack_configure first
                    val error = assertFailsWith<ConnectionException> { connection.roundtrip() }
                    assertTrue(error.message!!.contains("acknowledged"), error.message)
                }
            }
        }
    }
}
