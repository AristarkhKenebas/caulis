package dev.caulis.wm

import dev.caulis.core.TestClient
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.tabletv2.ZwpTabletManagerV2
import dev.caulis.protocol.client.tabletv2.ZwpTabletPadV2
import dev.caulis.protocol.client.tabletv2.ZwpTabletSeatV2
import dev.caulis.protocol.client.tabletv2.ZwpTabletToolV2
import dev.caulis.protocol.client.tabletv2.ZwpTabletV2
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlSurface
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drawing tablets: the pen as the pointer, as a pen for programs that take tablet events, and never past the lock. */
class TabletTest {

    private val harness = CaulisTest()

    // What a program's pointer heard (enter, motion, buttons), in a few words each.
    private fun listen(pointer: WlPointer, into: MutableList<String>) {
        pointer.events = object : WlPointer.Events {
            override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
                into += "enter ${surfaceX.toInt()} ${surfaceY.toInt()}"
            }
            override fun motion(proxy: WlPointer, time: Int, surfaceX: Double, surfaceY: Double) {
                into += "motion ${surfaceX.toInt()} ${surfaceY.toInt()}"
            }
            override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) {
                into += "button $button ${if (state == WlPointer.ButtonState.PRESSED) "down" else "up"}"
            }
        }
    }

    // A program's tablet seat: what its tools and pads heard. [tool] is the last tool announced.
    private class TabletSeat(client: TestClient) {
        val events: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val tablets: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        @Volatile var tool: ZwpTabletToolV2? = null
        @Volatile var serial = 0

        init {
            val tabletSeat = client.bind("zwp_tablet_manager_v2", ZwpTabletManagerV2, 1).getTabletSeat(client.seat)
            tabletSeat.events = object : ZwpTabletSeatV2.Events {
                override fun tabletAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletV2) {
                    id.events = object : ZwpTabletV2.Events {
                        override fun name(proxy: ZwpTabletV2, name: String) { tablets += name }
                    }
                }

                override fun toolAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletToolV2) {
                    tool = id
                    id.events = object : ZwpTabletToolV2.Events {
                        override fun proximityIn(proxy: ZwpTabletToolV2, serial: Int, tablet: ZwpTabletV2, surface: WlSurface) {
                            this@TabletSeat.serial = serial
                            events += "in"
                        }
                        override fun proximityOut(proxy: ZwpTabletToolV2) { events += "out" }
                        override fun motion(proxy: ZwpTabletToolV2, x: Double, y: Double) { events += "motion ${x.toInt()} ${y.toInt()}" }
                        override fun pressure(proxy: ZwpTabletToolV2, pressure: Int) { events += "pressure $pressure" }
                        override fun distance(proxy: ZwpTabletToolV2, distance: Int) { events += "distance $distance" }
                        override fun tilt(proxy: ZwpTabletToolV2, tiltX: Double, tiltY: Double) { events += "tilt ${tiltX.toInt()} ${tiltY.toInt()}" }
                        override fun down(proxy: ZwpTabletToolV2, serial: Int) { events += "down" }
                        override fun up(proxy: ZwpTabletToolV2) { events += "up" }
                        override fun button(proxy: ZwpTabletToolV2, serial: Int, button: Int, state: Int) {
                            events += "button $button ${if (state == ZwpTabletToolV2.ButtonState.PRESSED) "down" else "up"}"
                        }
                        override fun frame(proxy: ZwpTabletToolV2, time: Int) { events += "frame" }
                    }
                }

                override fun padAdded(proxy: ZwpTabletSeatV2, id: ZwpTabletPadV2) {
                    id.events = object : ZwpTabletPadV2.Events {
                        override fun enter(proxy: ZwpTabletPadV2, serial: Int, tablet: ZwpTabletV2, surface: WlSurface) { events += "pad enter" }
                        override fun button(proxy: ZwpTabletPadV2, time: Int, button: Int, state: Int) {
                            events += "pad button $button ${if (state == ZwpTabletPadV2.ButtonState.PRESSED) "down" else "up"}"
                        }
                    }
                }
            }
        }
    }

    // Lets the compositor's answers arrive (a few round trips).
    private fun TestClient.settle() {
        var looks = 0
        waitFor { ++looks > 3 }
    }

    @Test
    fun `a pen is the pointer for programs without tablet events - it moves it and clicks`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val window = client.window { it.setAppId("plain") }.also { it.draw(300, 200) }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "plain" }
            val heard = Collections.synchronizedList(ArrayList<String>())
            listen(client.seat.getPointer(), heard)
            client.settle()
            val rect = caulis.onLoop { r -> r.desktop.shownRect(r.desktop.allWindows.single())!! }
            val x = rect.x + 40.0
            val y = rect.y + 30.0
            caulis.onLoop { caulis.simulatePen(x, y) }
            assertEquals(x to y, caulis.onLoop { r -> r.desktop.pointerX to r.desktop.pointerY }, "the pointer is where the pen is")
            caulis.onLoop {
                caulis.simulatePen(x, y, 0.6, down = true)
                caulis.simulatePen(x, y, 0.0, down = false)
                caulis.simulatePenButton(BTN_STYLUS, true) // the lower button: a right click
                caulis.simulatePenButton(BTN_STYLUS, false)
                caulis.simulatePenAway()
            }
            client.waitFor { heard.count { it.startsWith("button") } >= 4 }
            assertEquals("enter 40 30", heard.first())
            assertEquals(listOf("button 272 down", "button 272 up", "button 273 down", "button 273 up"), heard.filter { it.startsWith("button") })
            window.toString()
        }
    }

    @Test
    fun `a program with tablet events gets the pen itself - proximity, motion, pressure, tip, frames, its cursor`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val window = client.window { it.setAppId("drawing") }.also { it.draw(300, 200) }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "drawing" }
            val tablet = TabletSeat(client)
            val heard = Collections.synchronizedList(ArrayList<String>())
            listen(client.seat.getPointer(), heard)
            client.waitFor { caulis.onLoop { r -> r.tablets.takes(r.desktop.allWindows.single().surface) } }
            val rect = caulis.onLoop { r -> r.desktop.shownRect(r.desktop.allWindows.single())!! }
            caulis.onLoop {
                caulis.simulatePen(rect.x + 10.0, rect.y + 20.0)
                caulis.simulatePen(rect.x + 15.0, rect.y + 25.0, 0.5, down = true)
                caulis.simulatePen(rect.x + 15.0, rect.y + 25.0, 0.0, down = false)
                caulis.simulatePenButton(BTN_STYLUS, true)
                caulis.simulatePenButton(BTN_STYLUS, false)
            }
            client.waitFor { tablet.events.count { it == "frame" } >= 7 }
            assertEquals(listOf("Caulis test tablet"), tablet.tablets.toList(), "the tablet was announced")
            assertEquals(
                listOf(
                    "in", "motion 10 20", "pressure 0", "distance 0", "tilt 0 0", "frame",
                    "motion 15 25", "pressure 32768", "frame", "down", "frame",
                    "motion 15 25", "pressure 0", "frame", "up", "frame",
                    "button 331 down", "frame", "button 331 up", "frame",
                ),
                tablet.events.toList(),
            )
            assertTrue(heard.none { it.startsWith("button") }, "no pointer clicks besides: $heard")

            // Its own cursor (none here) while the pen is over it.
            tablet.tool!!.setCursor(tablet.serial, null, 0, 0)
            client.waitFor { caulis.onLoop { r -> r.desktop.cursorShape } == null }

            caulis.onLoop { caulis.simulatePenAway() }
            client.waitFor { "out" in tablet.events }
            assertEquals(listOf("out", "frame"), tablet.events.takeLast(2))
            assertEquals("default", caulis.onLoop { r -> r.desktop.cursorShape }, "the cursor is the compositor's again")
            window.toString()
        }
    }

    @Test
    fun `pad buttons go to the program with the keyboard, unless bound`() = with(harness) {
        val bound = AtomicInteger()
        withCaulis({ bind("Pad2") { bound.incrementAndGet() } }) { caulis, client ->
            client.window { it.setAppId("pads") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "pads" }
            val tablet = TabletSeat(client)
            client.waitFor { caulis.onLoop { r -> r.tablets.takes(r.desktop.allWindows.single().surface) } }
            caulis.onLoop {
                caulis.simulatePadButton(0, true)
                caulis.simulatePadButton(0, false)
                caulis.simulatePadButton(1, true) // "Pad2": the compositor's
                caulis.simulatePadButton(1, false)
            }
            client.waitFor { tablet.events.count { it.startsWith("pad button") } >= 2 }
            client.settle()
            assertEquals(listOf("pad enter", "pad button 0 down", "pad button 0 up"), tablet.events.toList())
            assertEquals(1, bound.get())
        }
    }

    @Test
    fun `while locked, programs behind the lock get nothing of the pen`() = with(harness) {
        withCaulis({}) { caulis, locker ->
            val socket = caulis.socketDirectory!!.resolve(caulis.onLoop { it.socket })
            TestClient(Connection.connect(socket)).use { app ->
                app.window { it.setAppId("behind") }.also { it.draw(300, 200) }
                app.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "behind" }
                val tablet = TabletSeat(app)
                val heard = Collections.synchronizedList(ArrayList<String>())
                listen(app.seat.getPointer(), heard)
                app.waitFor { caulis.onLoop { r -> r.tablets.takes(r.desktop.allWindows.single().surface) } }
                val rect = caulis.onLoop { r -> r.desktop.shownRect(r.desktop.allWindows.single())!! }
                // The pen over the program first: it has it.
                caulis.onLoop { caulis.simulatePen(rect.x + 10.0, rect.y + 10.0) }
                app.waitFor { "in" in tablet.events }

                var locked = false
                locker.sessionLock!!.lock().events = object : ExtSessionLockV1.Events {
                    override fun locked(proxy: ExtSessionLockV1) { locked = true }
                }
                locker.waitFor { locked }
                val before = tablet.events.size
                val pointerBefore = heard.size
                caulis.onLoop {
                    caulis.simulatePen(rect.x + 20.0, rect.y + 20.0, 0.7, down = true)
                    caulis.simulatePen(rect.x + 30.0, rect.y + 30.0, 0.7)
                    caulis.simulatePenButton(BTN_STYLUS, true)
                    caulis.simulatePenButton(BTN_STYLUS, false)
                    caulis.simulatePen(rect.x + 30.0, rect.y + 30.0, 0.0, down = false)
                    caulis.simulatePadButton(0, true)
                    caulis.simulatePadButton(0, false)
                }
                app.settle()
                val after = tablet.events.drop(before)
                assertTrue(after.all { it == "out" || it == "frame" }, "only the pen leaving: $after")
                assertTrue(heard.drop(pointerBefore).isEmpty(), "no pointer events: ${heard.drop(pointerBefore)}")
                assertNull(caulis.onLoop { r -> r.seat.penFocus }, "the pen is over no program")
            }
        }
    }

    private companion object {
        const val BTN_STYLUS = 0x14b
    }
}
