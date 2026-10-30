package dev.caulis.core

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.wayland.WlDataDevice
import dev.caulis.protocol.client.wayland.WlDataDeviceManager
import dev.caulis.protocol.client.wayland.WlDataOffer
import dev.caulis.protocol.client.wayland.WlDataSource
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.render.assumeGpu
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drag and drop between two programs: offered while over the target, dropped where accepted. */
class DragAndDropTest {

    @Test
    fun `a drag from one program is dropped on another that accepted it`() = withServer { server, from ->
        TestClient(Connection.connect(server.socketPath)).use { to ->
            // Side by side.
            server.onServer { d ->
                d.layout = dev.caulis.tiling.Columns(dev.caulis.tiling.Gaps(0, 0))
                d.animationMillis = 0
            }
            val source = from.window { it.setTitle("from") }.also { it.draw() }
            from.waitFor { server.onServer { d -> d.windows.size } == 1 }
            val target = to.window { it.setTitle("to") }.also { it.draw() }
            to.waitFor { server.onServer { d -> d.windows.size } == 2 }
            val (fromWindow, toWindow) = server.onServer { d -> d.windows.single { it.title == "from" } to d.windows.single { it.title == "to" } }
            // (The first window drew itself whole-screen wide: now at its half.)
            from.waitFor { source.width in 1..400 }
            source.draw()
            from.waitFor { server.onServer { d -> d.screenRect(fromWindow)?.width in 1..400 } }

            // The dragging program: the serial of its press.
            var pressSerial = 0
            from.seat.getPointer().events = object : WlPointer.Events {
                override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) {
                    pressSerial = serial
                }
            }
            val fromDevice = from.bind("wl_data_device_manager", WlDataDeviceManager, 3)
            val dragged = fromDevice.createDataSource()
            var dropPerformed = false
            var cancelled = false
            dragged.events = object : WlDataSource.Events {
                override fun dndDropPerformed(proxy: WlDataSource) {
                    dropPerformed = true
                }

                override fun cancelled(proxy: WlDataSource) {
                    cancelled = true
                }
            }
            dragged.offer("text/plain")
            dragged.setActions(1) // copy
            val device = fromDevice.getDataDevice(from.seat)

            // The target accepts text, copying.
            var entered = false
            var dropped = false
            val toManager = to.bind("wl_data_device_manager", WlDataDeviceManager, 3)
            toManager.getDataDevice(to.seat).events = object : WlDataDevice.Events {
                override fun enter(proxy: WlDataDevice, serial: Int, surface: WlSurface, x: Double, y: Double, id: WlDataOffer?) {
                    entered = true
                    id?.accept(serial, "text/plain")
                    id?.setActions(1, 1)
                }

                override fun drop(proxy: WlDataDevice) {
                    dropped = true
                }
            }
            from.connection.roundtrip()
            to.connection.roundtrip()

            server.onServer { d ->
                d.pointerMoved(0, fromWindow.x + 10.0, fromWindow.y + 10.0)
                d.pointerButton(0, BTN_LEFT, true)
            }
            from.waitFor { pressSerial != 0 }
            device.startDrag(dragged, source.surface, null, pressSerial)
            from.connection.roundtrip()
            server.onServer { d ->
                d.pointerMoved(0, toWindow.x + 10.0, toWindow.y + 10.0)
            }
            to.waitFor { entered }
            to.connection.roundtrip() // (its accept reached the compositor)
            server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
            to.waitFor { dropped }
            from.waitFor { dropPerformed }
            assertFalse(cancelled)
            target.surface.commit()
        }
    }

    // Two programs side by side; [from] started dragging text from its window, the pointer is
    // over [to]'s window now (which accepts it, if [accepts]).
    private class Dragging(val from: TestClient, val to: TestClient, val fromWindow: Window, val toWindow: Window) {
        @Volatile var entered = false
        @Volatile var left = false
        @Volatile var dropped = false
        @Volatile var dropPerformed = false
        @Volatile var cancelled = false
    }

    private fun dragging(server: ScreenTestServer, from: TestClient, to: TestClient, accepts: Boolean = true): Dragging {
        server.onServer { d ->
            d.layout = dev.caulis.tiling.Columns(dev.caulis.tiling.Gaps(0, 0))
            d.animationMillis = 0
        }
        val source = from.window { it.setTitle("from") }.also { it.draw() }
        from.waitFor { server.onServer { d -> d.windows.size } == 1 }
        to.window { it.setTitle("to") }.also { it.draw() }
        to.waitFor { server.onServer { d -> d.windows.size } == 2 }
        val (fromWindow, toWindow) = server.onServer { d -> d.windows.single { it.title == "from" } to d.windows.single { it.title == "to" } }
        from.waitFor { source.width in 1..400 }
        source.draw()
        from.waitFor { server.onServer { d -> d.screenRect(fromWindow)?.width in 1..400 } }
        val drag = Dragging(from, to, fromWindow, toWindow)
        var pressSerial = 0
        from.seat.getPointer().events = object : WlPointer.Events {
            override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) {
                pressSerial = serial
            }
        }
        val fromDevice = from.bind("wl_data_device_manager", WlDataDeviceManager, 3)
        val dragged = fromDevice.createDataSource()
        dragged.events = object : WlDataSource.Events {
            override fun dndDropPerformed(proxy: WlDataSource) { drag.dropPerformed = true }
            override fun cancelled(proxy: WlDataSource) { drag.cancelled = true }
        }
        dragged.offer("text/plain")
        dragged.setActions(1)
        val device = fromDevice.getDataDevice(from.seat)
        to.bind("wl_data_device_manager", WlDataDeviceManager, 3).getDataDevice(to.seat).events = object : WlDataDevice.Events {
            override fun enter(proxy: WlDataDevice, serial: Int, surface: WlSurface, x: Double, y: Double, id: WlDataOffer?) {
                drag.entered = true
                if (accepts) {
                    id?.accept(serial, "text/plain")
                    id?.setActions(1, 1)
                }
            }
            override fun leave(proxy: WlDataDevice) { drag.left = true }
            override fun drop(proxy: WlDataDevice) { drag.dropped = true }
        }
        from.connection.roundtrip()
        to.connection.roundtrip()
        server.onServer { d ->
            d.pointerMoved(0, fromWindow.x + 10.0, fromWindow.y + 10.0)
            d.pointerButton(0, BTN_LEFT, true)
        }
        from.waitFor { pressSerial != 0 }
        device.startDrag(dragged, source.surface, null, pressSerial)
        from.connection.roundtrip()
        server.onServer { d -> d.pointerMoved(0, toWindow.x + 10.0, toWindow.y + 10.0) }
        to.waitFor { drag.entered }
        to.connection.roundtrip()
        return drag
    }

    @Test
    fun `dropped where nothing was accepted, the drag is cancelled`() = withServer { server, from ->
        TestClient(Connection.connect(server.socketPath)).use { to ->
            val drag = dragging(server, from, to, accepts = false)
            server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
            from.waitFor { drag.cancelled }
            to.connection.roundtrip()
            assertFalse(drag.dropped)
            assertFalse(drag.dropPerformed)
        }
    }

    @Test
    fun `dropped over one of our widgets, the window it was over before does not get it`() = withServer { server, from ->
        TestClient(Connection.connect(server.socketPath)).use { to ->
            val drag = dragging(server, from, to)
            val bar = object : Widget {
                override fun area(monitor: Monitor) = dev.caulis.geometry.Rect(drag.toWindow.x + 50, drag.toWindow.y + 50, 40, 40)
                override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: dev.caulis.geometry.Rect) = canvas.fill(area, 0f, 0f, 1f)
            }
            server.onServer { d ->
                d.addWidget(bar)
                d.pointerMoved(0, drag.toWindow.x + 60.0, drag.toWindow.y + 60.0)
            }
            to.waitFor { drag.left }
            server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
            from.waitFor { drag.cancelled }
            to.connection.roundtrip()
            assertFalse(drag.dropped, "no drop where the pointer is not")
        }
    }

    @Test
    fun `the target program crashing mid-drag cancels the drop, the compositor goes on`() = withServer { server, from ->
        val to = TestClient(Connection.connect(server.socketPath))
        val drag = dragging(server, from, to)
        to.close()
        from.waitFor { server.onServer { d -> d.windows.size } == 1 }
        server.onServer { d -> d.pointerButton(0, BTN_LEFT, false) }
        from.waitFor { drag.cancelled }
        assertFalse(drag.dropPerformed)
    }

    @Test
    fun `the dragging program crashing mid-drag ends the drag`() = withServer { server, _ ->
        val from = TestClient(Connection.connect(server.socketPath))
        TestClient(Connection.connect(server.socketPath)).use { to ->
            val drag = dragging(server, from, to)
            from.close()
            to.waitFor { drag.left }
            to.waitFor { server.onServer { d -> d.windows.size } == 1 }
            server.onServer { d ->
                d.pointerButton(0, BTN_LEFT, false)
                // The pointer is the pointer again: the window under it gets it.
                d.pointerMoved(0, drag.toWindow.x + 20.0, drag.toWindow.y + 20.0)
                assertTrue(d.pointerWindow === drag.toWindow)
            }
            to.connection.roundtrip()
            assertFalse(drag.dropped)
        }
    }

    @Test
    fun `the session locking mid-drag cancels it`() = withServer { server, from ->
        TestClient(Connection.connect(server.socketPath)).use { to ->
            val drag = dragging(server, from, to)
            val screen = object : Widget {
                override fun area(monitor: Monitor) = dev.caulis.geometry.Rect(0, 0, 10, 10)
                override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: dev.caulis.geometry.Rect) = canvas.fill(area, 0f, 0f, 1f)
            }
            server.onServer { d -> d.lockInternally(screen) }
            from.waitFor { drag.cancelled }
            to.waitFor { drag.left }
            server.onServer { d ->
                d.pointerButton(0, BTN_LEFT, false)
                d.unlockInternally()
            }
            to.connection.roundtrip()
            assertFalse(drag.dropped)
        }
    }

    @Test
    fun `a drag without a button held is refused`() = withServer { server, client ->
        val window = client.window().also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        val manager = client.bind("wl_data_device_manager", WlDataDeviceManager, 3)
        val dragged = manager.createDataSource()
        var cancelled = false
        dragged.events = object : WlDataSource.Events {
            override fun cancelled(proxy: WlDataSource) {
                cancelled = true
            }
        }
        dragged.offer("text/plain")
        manager.getDataDevice(client.seat).startDrag(dragged, window.surface, null, 1)
        client.waitFor { cancelled }
        assertTrue(server.onServer { d -> d.windows.size } == 1)
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BTN_LEFT = 0x110
    }
}
