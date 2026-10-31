package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.protocol.client.xdgshell.XdgPopup
import dev.caulis.protocol.client.xdgshell.XdgPositioner
import dev.caulis.protocol.client.xdgshell.XdgSurface
import dev.caulis.render.assumeGpu
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Panels, docks and launchers (wlr-layer-shell). */
class LayerShellTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    // A layer surface as a bar or launcher makes it; returns it with its configured size.
    private class Panel(val surface: WlSurface, val layer: ZwlrLayerSurfaceV1) {
        var width = 0
        var height = 0
    }

    private fun TestClient.panel(layer: Int, anchor: Int, width: Int, height: Int, exclusive: Int = 0, keyboard: Int = 0): Panel {
        val surface = compositor.createSurface()
        val layerSurface = layerShell!!.getLayerSurface(surface, null, layer, "test")
        val panel = Panel(surface, layerSurface)
        layerSurface.events = object : ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) {
                panel.width = width
                panel.height = height
                proxy.ackConfigure(serial)
            }
        }
        layerSurface.setSize(width, height)
        layerSurface.setAnchor(anchor)
        layerSurface.setExclusiveZone(exclusive)
        layerSurface.setKeyboardInteractivity(keyboard)
        surface.commit()
        connection.roundtrip()
        return panel
    }

    @Test
    fun `a panel's new pixels leave the scene, its new height does not`() = withServer { server, client ->
        var last: IntArray? = null
        server.onServer { d -> d.backgroundChangeMillis = 0; d.background = Background.gradient(dev.caulis.render.Color.hex("#102030"), dev.caulis.render.Color.hex("#f0e0d0"), degrees = 30.0) }
        val bar = client.panel(layer = 2, anchor = TOP or LEFT or RIGHT, width = 0, height = 30, exclusive = 30)
        client.draw(bar.surface, bar.width, bar.height)
        client.waitFor { server.onServer { it.usableArea } == Rect(0, 30, width, height - 30) }
        client.connection.roundtrip()
        Thread.sleep(200)
        server.onFrameShown = { _, pixels -> last = pixels }
        val version = server.onServer { it.version }
        val red = 0xffff0000.toInt()
        val (buffer, memory) = client.buffer(bar.width, bar.height)
        memory.write { s -> for (i in 0 until bar.width * bar.height) s.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i.toLong(), red) }
        bar.surface.attach(buffer, 0, 0)
        bar.surface.damageBuffer(0, 0, bar.width, bar.height)
        bar.surface.commit()
        client.waitFor { last?.contains(red) == true }
        kotlin.test.assertEquals(version, server.onServer { it.version }, "new pixels: the same scene")
        kotlin.test.assertContentEquals(server.screenshot(), last, "and the screen as a whole redraw shows it")
        // Another height, asked for: placed anew, windows make way.
        bar.layer.setSize(0, 60)
        bar.layer.setExclusiveZone(60)
        bar.surface.commit()
        client.connection.roundtrip()
        client.draw(bar.surface, bar.width, 60)
        client.waitFor { server.onServer { it.usableArea } == Rect(0, 60, width, height - 60) }
    }

    @Test
    fun `a bar along the top keeps its space free of windows`() = withServer { server, client ->
        server.onServer { it.layout = Dwindle(Gaps(0, 0)) }
        val bar = client.panel(layer = 2, anchor = TOP or LEFT or RIGHT, width = 0, height = 30, exclusive = 30)
        assertEquals(width to 30, bar.width to bar.height, "stretched across the top")
        client.draw(bar.surface, bar.width, bar.height)
        client.waitFor { server.onServer { it.usableArea } == Rect(0, 30, width, height - 30) }

        val window = client.window()
        assertEquals(width to height - 30, window.width to window.height, "the window gets the rest")
        window.draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.y } == 30 }

        // The bar goes away: the window gets the whole screen.
        bar.layer.destroy()
        bar.surface.destroy()
        client.waitFor { window.height == height }
    }

    @Test
    fun `a launcher that wants the keyboard gets it while it is open`() = withServer { server, client ->
        val window = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        val launcher = client.panel(layer = 3, anchor = 0, width = 300, height = 200, keyboard = 1)
        assertEquals(300 to 200, launcher.width to launcher.height)
        client.draw(launcher.surface, 300, 200)
        client.waitFor { server.onServer { it.layers.any { l -> l.mapped } } }
        assertEquals("test", server.onServer { it.keyboardFocus?.layerNamespace() })
        val placed = server.onServer { d -> d.layers.single().let { Rect(it.x, it.y, 300, 200) } }
        assertEquals(Rect((width - 300) / 2, (height - 200) / 2, 300, 200), placed, "no anchor: centred")

        launcher.layer.destroy()
        launcher.surface.destroy()
        client.waitFor { server.onServer { it.keyboardFocus?.roleObject is Window } }
        window.draw()
    }

    @Test
    fun `a panel's menu opens next to the panel`() = withServer { server, client ->
        val bar = client.panel(layer = 2, anchor = BOTTOM or LEFT or RIGHT, width = 0, height = 40, exclusive = 40)
        client.draw(bar.surface, bar.width, bar.height)
        client.waitFor { server.onServer { it.layers.any { l -> l.mapped } } }

        // A menu without a parent, adopted by the panel; opening above its left end.
        val menuSurface = client.compositor.createSurface()
        val xdgSurface = client.wmBase.getXdgSurface(menuSurface)
        val positioner = client.wmBase.createPositioner()
        positioner.setSize(100, 150)
        positioner.setAnchorRect(0, 0, 50, 40)
        positioner.setAnchor(XdgPositioner.Anchor.TOP_LEFT)
        positioner.setGravity(XdgPositioner.Gravity.TOP_RIGHT)
        val popup = xdgSurface.getPopup(null, positioner)
        bar.layer.getPopup(popup)
        var configured = false
        xdgSurface.events = object : XdgSurface.Events {
            override fun configure(proxy: XdgSurface, serial: Int) {
                proxy.ackConfigure(serial)
                configured = true
            }
        }
        popup.events = object : XdgPopup.Events {}
        menuSurface.commit()
        client.waitFor { configured }
        client.draw(menuSurface, 100, 150)
        client.waitFor { server.onServer { it.openPopups.isNotEmpty() } }
        val shown = server.onServer { d -> d.openPopups.single().let(d::screenRect) }
        assertEquals(Rect(0, height - 40 - 150, 100, 150), shown, "above the bar's left end")
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private fun Surface.layerNamespace() = (roleObject as? LayerSurface)?.namespace

    private companion object {
        const val TOP = 1
        const val BOTTOM = 2
        const val LEFT = 4
        const val RIGHT = 8
    }
}
