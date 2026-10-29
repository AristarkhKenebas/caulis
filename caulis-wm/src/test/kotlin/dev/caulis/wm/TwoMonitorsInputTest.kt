package dev.caulis.wm

import dev.caulis.protocol.client.wayland.WlKeyboard
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Two monitors of different sizes and scales, side by side, above, to the left, with offsets:
 * the window under the pointer gets the pointer at the right point of its own, a click gives
 * it the keyboard, and the keys go to it — on either monitor, back and forth.
 */
class TwoMonitorsInputTest {

    private val harness = CaulisTest()

    private class Setup(val name: String, val first: Triple<Int, Int, Double>, val second: Triple<Int, Int, Double>, val secondAt: Pair<Int, Int>? = null)

    private val setups = listOf(
        Setup("2560x1440 and 1920x1080 beside it", Triple(2560, 1440, 1.0), Triple(1920, 1080, 1.0)),
        Setup("2880x1800 at 2 and 1920x1080 at 1", Triple(2880, 1800, 2.0), Triple(1920, 1080, 1.0)),
        Setup("1920x1080 at 1 and 2880x1800 at 2 to its left, lower", Triple(1920, 1080, 1.0), Triple(2880, 1800, 2.0), -1440 to 300),
        Setup("2560x1440 at 1.25 and 1920x1080 above it, shifted", Triple(2560, 1440, 1.25), Triple(1920, 1080, 1.0), 100 to -1080),
    )

    // What the program heard last, per surface.
    private class Heard {
        @Volatile var pointerOn: WlSurface? = null
        @Volatile var pointerAt = 0.0 to 0.0
        @Volatile var keyboardOn: WlSurface? = null
        val keys = java.util.concurrent.CopyOnWriteArrayList<Pair<WlSurface?, Int>>()
        val buttons = java.util.concurrent.CopyOnWriteArrayList<WlSurface?>()
    }

    @Test
    fun `pointer, clicks and keys reach the window under the pointer on each monitor`() = with(harness) {
        for (setup in setups) {
            withCaulis({
                headlessMonitors = 2
                focusFollowsMouse = false
                monitor("HEADLESS-1") { mode(setup.first.first, setup.first.second); scale = setup.first.third; position(0, 0) }
                monitor("HEADLESS-2") {
                    mode(setup.second.first, setup.second.second); scale = setup.second.third
                    val at = setup.secondAt ?: ((setup.first.first / setup.first.third).toInt() to 0)
                    position(at.first, at.second)
                }
            }, headless = true) { caulis, client ->
                val heard = Heard()
                client.seat.getPointer().events = object : WlPointer.Events {
                    override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) {
                        heard.pointerOn = surface; heard.pointerAt = surfaceX to surfaceY
                    }
                    override fun leave(proxy: WlPointer, serial: Int, surface: WlSurface) { if (heard.pointerOn === surface) heard.pointerOn = null }
                    override fun motion(proxy: WlPointer, time: Int, surfaceX: Double, surfaceY: Double) { heard.pointerAt = surfaceX to surfaceY }
                    override fun button(proxy: WlPointer, serial: Int, time: Int, button: Int, state: Int) { if (state == 1) heard.buttons += heard.pointerOn }
                }
                client.seat.getKeyboard().events = object : WlKeyboard.Events {
                    override fun enter(proxy: WlKeyboard, serial: Int, surface: WlSurface, keys: ByteArray) { heard.keyboardOn = surface }
                    override fun leave(proxy: WlKeyboard, serial: Int, surface: WlSurface) { if (heard.keyboardOn === surface) heard.keyboardOn = null }
                    override fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) { if (state == 1) heard.keys += heard.keyboardOn to key }
                }
                val (one, two) = caulis.onLoop { r -> r.desktop.monitors.let { it[0] to it[1] } }
                // A window on each monitor (new windows open on the monitor with the focus).
                val windows = listOf(one, two).mapIndexed { index, monitor ->
                    caulis.onLoop { r -> r.desktop.focusMonitor(monitor) }
                    val window = client.window { it.setAppId("w$index") }.also { it.draw() }
                    client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.any { it.appId == "w$index" } } }
                    window
                }
                // Drawn at the size asked, so the whole window takes input.
                var looks = 0
                client.waitFor { ++looks > 5 }
                windows.forEach { it.draw() }
                client.waitFor {
                    caulis.onLoop { r -> r.desktop.allWindows.all { w -> w.visibleRect.width > 0 && w.surface.width == windows[w.appId.drop(1).toInt()].width.takeIf { it > 0 } ?: 64 } }
                }
                caulis.onLoop { r ->
                    for ((index, monitor) in listOf(one, two).withIndex()) {
                        val w = r.desktop.allWindows.first { it.appId == "w$index" }
                        assertSame(monitor, r.desktop.monitorOf(w), "${setup.name}: w$index is on ${monitor.name}")
                    }
                }
                // Back and forth: each window in turn, at a point a little off its middle.
                for (round in 0 until 2) for (index in listOf(1, 0)) {
                    val (x, y, w) = caulis.onLoop { r ->
                        val w = r.desktop.allWindows.first { it.appId == "w$index" }
                        Triple(w.x + (w.visibleRect.width / 3).toDouble() + 0.5, w.y + (w.visibleRect.height / 4).toDouble() + 0.25, w)
                    }
                    caulis.onLoop { r -> r.pointerMovedTo(0, x, y) }
                    client.waitFor { heard.pointerOn === windows[index].surface }
                    val expected = (x - w.x) to (y - w.y)
                    // (Sent as fixed point numbers: 1/256 apart.)
                    fun near() = Math.abs(heard.pointerAt.first - expected.first) < 0.01 && Math.abs(heard.pointerAt.second - expected.second) < 0.01
                    runCatching { client.waitFor { near() } }
                    assertTrue(near(), "${setup.name}: the point inside w$index is $expected, the program heard ${heard.pointerAt}")
                    caulis.onLoop { r -> r.pointerButton(0, BTN_LEFT, true); r.pointerButton(0, BTN_LEFT, false) }
                    client.waitFor { heard.keyboardOn === windows[index].surface }
                    caulis.onLoop { r -> r.key(0, KEY_A, true); r.key(0, KEY_A, false) }
                    client.waitFor { heard.keys.isNotEmpty() }
                    assertEquals(listOf(windows[index].surface to KEY_A), heard.keys.toList(), "${setup.name}: the key went to w$index")
                    assertTrue(heard.buttons.single() === windows[index].surface, "${setup.name}: the click went to w$index")
                    heard.keys.clear()
                    heard.buttons.clear()
                    // Only the clicked one looks focused (the other monitor's window is not active any more).
                    assertEquals(listOf("w$index"), caulis.onLoop { r -> r.desktop.allWindows.filter { it.activated }.map { it.appId } }, "${setup.name}: active")
                    assertSame(caulis.onLoop { r -> r.desktop.monitorOf(r.desktop.allWindows.first { it.appId == "w$index" }) }, caulis.onLoop { r -> r.desktop.focusedMonitor }, "${setup.name}: the clicked one's monitor has the focus")
                }
            }
        }
    }

    companion object {
        const val BTN_LEFT = 0x110
        const val KEY_A = 30
    }
}
