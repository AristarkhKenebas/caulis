package dev.caulis.wm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The laptop's display modes: what is on, what mirrors, what is kept for next time, the lid. */
class DisplaysTest {

    private val harness = CaulisTest()

    // Monitors as the screen backend has them: turned on as mirrors when the modes say so.
    private class FakeScreens : Displays.Screens {
        val all = LinkedHashMap<String, String>() // connector -> who it is
        val on = HashSet<String>()
        val mirrors = HashSet<String>()
        lateinit var displays: Displays

        override fun plugged() = all.map { (name, id) -> Displays.Screen(name, id, name in on) }
        override fun setEnabled(name: String, on: Boolean) {
            if (on) {
                this.on += name
                if (displays.mirrors(name)) mirrors += name else mirrors -= name
            } else {
                this.on -= name
                mirrors -= name
            }
        }
        override fun mirroring(name: String) = name in mirrors

        fun plug(name: String, id: String) {
            all[name] = id
            displays.pluggedChanged(plugged())
            if (displays.wants(name)) setEnabled(name, true) // (as the backend's scan does next)
        }

        fun unplug(name: String) {
            all -= name
            on -= name
            mirrors -= name
            displays.pluggedChanged(plugged())
        }
    }

    @Test
    fun `modes turn monitors on, off and into mirrors, and are kept for the same monitors`() = with(harness) {
        val state = Files.createTempFile("displays", "")
        try {
            withCaulis({}, headless = true) { caulis, _ ->
                val screens = FakeScreens()
                caulis.onLoop { r ->
                    screens.displays = Displays(r, r.loop, screens, state)
                    screens.plug("eDP-1", "BOE laptop")
                    screens.plug("HDMI-A-1", "EPS projector 123")
                }
                caulis.onLoop { } // (what the plug posted)
                caulis.onLoop { r ->
                    assertEquals(DisplayMode.EXTEND, screens.displays.mode, "a new monitor: beside the laptop's screen")
                    assertEquals(setOf("eDP-1", "HDMI-A-1"), screens.on)
                    assertEquals("Screens", r.desktop.onScreenDisplay, "the choice is shown")

                    screens.displays.use(DisplayMode.MIRROR)
                    assertEquals(setOf("eDP-1", "HDMI-A-1"), screens.on)
                    assertEquals(setOf("HDMI-A-1"), screens.mirrors, "the projector shows the laptop's screen")

                    screens.displays.use(DisplayMode.EXTERNAL_ONLY)
                    assertEquals(setOf("HDMI-A-1"), screens.on, "the laptop's screen is dark")
                    assertTrue(screens.mirrors.isEmpty(), "the projector is a monitor of its own again")

                    screens.displays.use(DisplayMode.BUILT_IN_ONLY)
                    assertEquals(setOf("eDP-1"), screens.on)

                    screens.displays.use(DisplayMode.EXTEND)
                    screens.displays.lid(closed = true)
                    assertEquals(setOf("HDMI-A-1"), screens.on, "the lid closed: the laptop's screen is off")
                    screens.displays.lid(closed = false)
                    assertEquals(setOf("eDP-1", "HDMI-A-1"), screens.on)

                    screens.displays.use(DisplayMode.EXTERNAL_ONLY)
                    screens.unplug("HDMI-A-1")
                }
                caulis.onLoop { }
                caulis.onLoop { assertEquals(setOf("eDP-1"), screens.on, "the projector gone: the laptop's screen is back") }

                // The same projector again, later: its mode as it was left.
                val again = FakeScreens()
                caulis.onLoop { r ->
                    again.displays = Displays(r, r.loop, again, state)
                    again.plug("eDP-1", "BOE laptop")
                    again.plug("HDMI-A-1", "EPS projector 123")
                }
                caulis.onLoop { }
                caulis.onLoop {
                    assertEquals(DisplayMode.EXTERNAL_ONLY, again.displays.mode)
                    assertEquals(setOf("HDMI-A-1"), again.on)
                }
            }
        } finally {
            Files.deleteIfExists(state)
        }
    }
}
