package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlCompositor
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Caulis's client side talking to Caulis's server side. */
class ClientSideTest {

    @Test
    fun `the client binds globals and receives events`() {
        TestServer().use { server ->
            Connection.connect(server.socketPath).use { connection ->
                val globals = HashMap<String, Pair<Int, Int>>()
                val registry = connection.display.getRegistry()
                registry.events = object : WlRegistry.Events {
                    override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                        globals[`interface`] = name to versionArg
                    }
                }
                connection.roundtrip()
                assertEquals(Compositor.VERSION, globals.getValue("wl_compositor").second)

                var outputName = ""
                var modeWidth = 0
                val output = registry.bind(globals.getValue("wl_output").first, WlOutput, 4)
                output.events = object : WlOutput.Events {
                    override fun name(proxy: WlOutput, name: String) {
                        outputName = name
                    }

                    override fun mode(proxy: WlOutput, flags: Int, width: Int, height: Int, refresh: Int) {
                        modeWidth = width
                    }
                }
                val compositor = registry.bind(globals.getValue("wl_compositor").first, WlCompositor, 6)
                val surface = compositor.createSurface()
                surface.commit()
                connection.roundtrip()

                assertEquals("HEADLESS-1", outputName)
                assertEquals(1920, modeWidth)
                assertTrue(surface.isAlive)
            }
        }
    }
}
