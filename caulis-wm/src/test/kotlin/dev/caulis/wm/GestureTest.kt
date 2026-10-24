package dev.caulis.wm

import dev.caulis.core.input.Gesture
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGestureSwipeV1
import dev.caulis.protocol.client.pointergesturesunstablev1.ZwpPointerGesturesV1
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.tiling.Canvas
import dev.caulis.tiling.Gaps
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Touchpad gestures, as the compositor uses them and passes them on. */
class GestureTest {

    private val harness = CaulisTest()

    @Test
    fun `three fingers drag the canvas, the focus follows to what is in view`() = with(harness) {
        withCaulis({
            layout = Canvas(Gaps(0, 0), columns = 2, rows = 1, windowColumns = 1, windowRows = 1)
            animationOff()
        }) { caulis, client ->
            for (n in 1..3) {
                client.window { it.setTitle("w$n") }.draw()
                client.waitFor { caulis.onLoop { it.desktop.windows.size } == n }
            }
            caulis.onLoop { r -> r.desktop.focus(r.desktop.windows.first { it.title == "w1" }) }
            val width = caulis.onLoop { it.desktop.width }
            // Fingers move a whole screen to the left: the view goes right by two cells.
            caulis.onLoop { r ->
                r.gesture(Gesture.SwipeBegin(0, 3))
                repeat(10) { r.gesture(Gesture.SwipeUpdate(0, -width / 10.0, 0.0)) }
            }
            val during = caulis.onLoop { r -> r.desktop.windows.first { it.title == "w1" }.let { r.desktop.shownRect(it)!!.x } }
            assertTrue(during < 0, "w1 moved with the fingers: $during")
            caulis.onLoop { r -> r.gesture(Gesture.SwipeEnd(0, cancelled = false)) }
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.first { it.title == "w3" }.x } == 0 } // two cells: w3 at the left edge
            assertEquals("w3", caulis.onLoop { it.desktop.focusedWindow?.title }, "w1 left the screen, so the window in view got the focus")
        }
    }

    @Test
    fun `four fingers pinch the overview open`() = with(harness) {
        withCaulis({ animationOff() }) { caulis, client ->
            client.window().draw()
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }
            caulis.onLoop { r ->
                r.gesture(Gesture.PinchBegin(0, 4))
                r.gesture(Gesture.PinchUpdate(0, 0.0, 0.0, scale = 0.6, rotation = 0.0))
                r.gesture(Gesture.PinchEnd(0, cancelled = false))
            }
            assertTrue(caulis.onLoop { it.desktop.inOverview })
        }
    }

    @Test
    fun `gestures the compositor does not use go to the program`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val received = CopyOnWriteArrayList<String>()
            val names = HashMap<String, Int>()
            client.connection.display.getRegistry().events = object : WlRegistry.Events {
                override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                    names[`interface`] = name
                    if (`interface` == "zwp_pointer_gestures_v1") {
                        val gestures = proxy.bind(name, ZwpPointerGesturesV1, 1)
                        gestures.getSwipeGesture(client.seat.getPointer()).events = object : ZwpPointerGestureSwipeV1.Events {
                            override fun begin(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, surface: WlSurface, fingers: Int) {
                                received += "begin $fingers"
                            }

                            override fun update(proxy: ZwpPointerGestureSwipeV1, time: Int, dx: Double, dy: Double) {
                                received += "update"
                            }

                            override fun end(proxy: ZwpPointerGestureSwipeV1, serial: Int, time: Int, cancelled: Int) {
                                received += "end"
                            }
                        }
                    }
                }
            }
            client.connection.roundtrip()
            client.window().draw(300, 300)
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }
            caulis.onLoop { r ->
                val w = r.desktop.windows.single()
                r.desktop.pointerMoved(0, w.x + 10.0, w.y + 10.0)
                r.gesture(Gesture.SwipeBegin(0, 5)) // five fingers: nothing of ours
                r.gesture(Gesture.SwipeUpdate(0, 1.0, 2.0))
                r.gesture(Gesture.SwipeEnd(0, cancelled = false))
            }
            client.waitFor { received.size == 3 }
            assertEquals(listOf("begin 5", "update", "end"), received)
        }
    }

    private fun Caulis.animationOff() = onStart { desktop.animationMillis = 0 }
}
