package dev.caulis.wm

import dev.caulis.core.Window
import dev.caulis.core.WindowDirector
import dev.caulis.core.WindowPlace
import dev.caulis.protocol.client.wayland.WlKeyboard
import dev.caulis.protocol.client.wayland.WlPointer
import dev.caulis.protocol.client.wayland.WlSurface
import dev.caulis.protocol.client.xdgshell.XdgPopup
import dev.caulis.protocol.client.xdgshell.XdgPositioner
import dev.caulis.protocol.client.xdgshell.XdgSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A compositor that places windows itself (as newm does, through a WindowDirector) on two
 * monitors of different sizes and scales, both windows opened on the first monitor's
 * workspace: the one drawn on the second monitor counts as there — its menus stay there,
 * it gets that monitor's scale — and clicks and keys reach it; only one window is active.
 */
class DirectedMonitorsTest {

    private val harness = CaulisTest()

    @Test
    fun `windows a director places on the second monitor get input, menus and scale there`() = with(harness) {
        // w0 on the first monitor (2880x1800 at 2: 1440x900 units), w1 on the second (1920x1080 at 1, right of it).
        val director = object : WindowDirector {
            override fun place(window: Window, now: Long): WindowPlace = when (window.appId) {
                "w1" -> WindowPlace(1440.0 + 200.0, 100.0, 800.0, 600.0)
                else -> WindowPlace(100.0, 100.0, 800.0, 600.0)
            }
            override fun initialSize(window: Window) = 800 to 600
        }
        withCaulis({
            headlessMonitors = 2
            monitor("HEADLESS-1") { mode(2880, 1800); scale = 2.0 }
            monitor("HEADLESS-2") { mode(1920, 1080); scale = 1.0 }
            this.director = director
        }, headless = true) { caulis, client ->
            var pointerOn: WlSurface? = null
            var pointerAt = 0.0 to 0.0
            var keyboardOn: WlSurface? = null
            val keys = ArrayList<WlSurface?>()
            client.seat.getPointer().events = object : WlPointer.Events {
                override fun enter(proxy: WlPointer, serial: Int, surface: WlSurface, surfaceX: Double, surfaceY: Double) { pointerOn = surface; pointerAt = surfaceX to surfaceY }
                override fun motion(proxy: WlPointer, time: Int, surfaceX: Double, surfaceY: Double) { pointerAt = surfaceX to surfaceY }
            }
            client.seat.getKeyboard().events = object : WlKeyboard.Events {
                override fun enter(proxy: WlKeyboard, serial: Int, surface: WlSurface, keys: ByteArray) { keyboardOn = surface }
                override fun key(proxy: WlKeyboard, serial: Int, time: Int, key: Int, state: Int) { if (state == 1) keys += keyboardOn }
            }
            // The scale each program is told to draw at.
            val scales = HashMap<WlSurface, Int>()
            val windows = (0..1).map { n ->
                client.window { it.setAppId("w$n") }.also { w ->
                    w.surface.events = object : WlSurface.Events {
                        override fun preferredBufferScale(proxy: WlSurface, factor: Int) { scales[proxy] = factor }
                    }
                    w.draw(800, 600)
                }
            }
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.count { r.desktop.screenRect(it) != null } } == 2 }
            val (one, two) = caulis.onLoop { r -> r.desktop.monitors.let { it[0] to it[1] } }
            caulis.onLoop { r ->
                val w1 = r.desktop.allWindows.first { it.appId == "w1" }
                assertSame(two, r.desktop.monitorOf(w1), "drawn on the second monitor: it is on the second monitor")
            }
            // The program of w1 draws at the second monitor's scale (it was told it is there).
            // (The first scale, 1, came before these listeners: a program hears it again only when it changes.)
            client.waitFor { scales[windows[0].surface] == 2 }
            assertEquals(listOf(2, 1), windows.map { scales[it.surface] ?: 1 }, "w0 at the first monitor's scale, w1 at the second's")

            for (index in listOf(1, 0, 1)) {
                val (x, y) = if (index == 1) 1640.0 + 300.5 to 100.0 + 200.25 else 100.0 + 300.5 to 100.0 + 200.25
                caulis.onLoop { r -> r.pointerMovedTo(0, x, y); r.pointerButton(0, BTN_LEFT, true); r.pointerButton(0, BTN_LEFT, false) }
                client.waitFor { pointerOn === windows[index].surface && keyboardOn === windows[index].surface }
                assertEquals(300.5 to 200.25, pointerAt, "w$index: the point inside it")
                caulis.onLoop { r -> r.key(0, KEY_A, true); r.key(0, KEY_A, false) }
                client.waitFor { keys.isNotEmpty() }
                assertEquals(listOf(windows[index].surface), keys.toList(), "the key went to w$index")
                keys.clear()
                caulis.onLoop { r ->
                    val active = r.desktop.allWindows.filter { it.activated }.map { it.appId }
                    assertEquals(listOf("w$index"), active, "only the clicked window is active")
                }
            }

            // A menu of w1 near its right edge: it stays on the second monitor (slid, not flipped onto the first).
            val menu = client.compositor.createSurface()
            val xdg = client.wmBase.getXdgSurface(menu)
            val positioner = client.wmBase.createPositioner()
            positioner.setSize(300, 200)
            positioner.setAnchorRect(0, 0, 1, 1)
            positioner.setAnchor(XdgPositioner.Anchor.TOP_LEFT)
            positioner.setGravity(XdgPositioner.Gravity.BOTTOM_LEFT)
            positioner.setConstraintAdjustment(XdgPositioner.ConstraintAdjustment.SLIDE_X or XdgPositioner.ConstraintAdjustment.SLIDE_Y)
            val popup = xdg.getPopup(windows[1].xdgSurface, positioner)
            var configured = false
            xdg.events = object : XdgSurface.Events {
                override fun configure(proxy: XdgSurface, serial: Int) { proxy.ackConfigure(serial); configured = true }
            }
            popup.events = object : XdgPopup.Events {}
            menu.commit()
            client.waitFor { configured }
            client.draw(menu, 300, 200)
            client.waitFor { caulis.onLoop { r -> r.desktop.openPopups.isNotEmpty() } }
            caulis.onLoop { r ->
                val shown = r.desktop.screenRect(r.desktop.openPopups.single())
                assertTrue(shown.x >= two.area.x && shown.right <= two.area.right, "the menu is on the second monitor: $shown (${two.area})")
                assertFalse(shown.x < one.area.right, "not on the first")
            }
        }
    }

    @Test
    fun `the special workspace shows on the monitor the director says is worked on`() = with(harness) {
        var work: dev.caulis.core.Monitor? = null
        val director = object : WindowDirector {
            override fun place(window: Window, now: Long): WindowPlace = WindowPlace(100.0, 100.0, 800.0, 600.0)
            override fun workMonitor() = work
        }
        withCaulis({
            headlessMonitors = 2
            monitor("HEADLESS-1") { mode(1920, 1080); scale = 1.0 }
            monitor("HEADLESS-2") { mode(2560, 1440); scale = 1.25 }
            this.director = director
        }, headless = true) { caulis, _ ->
            caulis.onLoop { r ->
                val d = r.desktop
                // None said: the desktop's focused monitor (the first).
                d.toggleSpecialWorkspace()
                assertSame(d.monitors[0], d.shownSpecialOn(d.monitors[0])?.let { d.monitors[0] })
                d.toggleSpecialWorkspace()
                // The second said: there, and toggled there again it hides.
                work = d.monitors[1]
                assertTrue(d.toggleSpecialWorkspace(), "shown")
                assertEquals("special", d.shownSpecialOn(d.monitors[1])?.name)
                assertEquals(null, d.shownSpecialOn(d.monitors[0]))
                assertFalse(d.toggleSpecialWorkspace(), "hidden")
                // Shown on the first, then worked on the second: it comes over.
                work = d.monitors[0]
                d.toggleSpecialWorkspace()
                work = d.monitors[1]
                assertTrue(d.toggleSpecialWorkspace(), "came over")
                assertEquals("special", d.shownSpecialOn(d.monitors[1])?.name)
            }
        }
    }

    companion object {
        const val BTN_LEFT = 0x110
        const val KEY_A = 30
    }
}
