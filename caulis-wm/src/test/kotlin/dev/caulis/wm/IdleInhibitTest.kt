package dev.caulis.wm

import dev.caulis.protocol.client.extidlenotifyv1.ExtIdleNotificationV1
import dev.caulis.protocol.client.extidlenotifyv1.ExtIdleNotifierV1
import dev.caulis.protocol.client.idleinhibitunstablev1.ZwpIdleInhibitManagerV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Something that starts keeping the computer awake while it is idle already (a video that
 * started by itself, a browser's D-Bus inhibitor held through inhibitIdle) counts as someone
 * back; what keeps it awake is said in words.
 */
class IdleInhibitTest {

    private val harness = CaulisTest()

    @Test
    fun `an inhibitor that comes while idle counts as someone back`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val notifier = client.bind("ext_idle_notifier_v1", ExtIdleNotifierV1, 1)
            var idled = 0
            var resumed = 0
            notifier.getIdleNotification(300, client.seat).events = object : ExtIdleNotificationV1.Events {
                override fun idled(proxy: ExtIdleNotificationV1) { idled++ }
                override fun resumed(proxy: ExtIdleNotificationV1) { resumed++ }
            }
            client.waitFor { idled == 1 }

            // A hold (a browser over D-Bus): someone is back, and it says why.
            val held = caulis.onLoop { caulis.inhibitIdle("Firefox: video-playing") }
            client.waitFor { resumed == 1 }
            assertEquals(listOf("Firefox: video-playing"), caulis.onLoop { caulis.idleInhibitedBy })
            // Held: no idle meanwhile.
            val until = System.nanoTime() + 700_000_000
            client.waitFor { System.nanoTime() > until }
            assertEquals(1, idled, "kept awake")
            caulis.onLoop { held.close() }
            client.waitFor { idled == 2 }
            assertEquals(emptyList(), caulis.onLoop { caulis.idleInhibitedBy })

            // A hold for a process with no window shown counts for nothing.
            val noWindow = caulis.onLoop { caulis.inhibitIdle("player", pid = Int.MAX_VALUE - 1) }
            assertEquals(emptyList(), caulis.onLoop { caulis.idleInhibitedBy })
            caulis.onLoop { noWindow.close() }

            // A video player's idle-inhibit made while idle: someone is back within a second or so.
            val video = client.window { it.setAppId("mpv") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.isNotEmpty() } }
            // (Opening a window is no input: still idle.)
            val resumedBefore = resumed
            client.bind("zwp_idle_inhibit_manager_v1", ZwpIdleInhibitManagerV1, 1).createInhibitor(video.surface)
            client.waitFor { resumed > resumedBefore }
            assertTrue("mpv" in caulis.onLoop { caulis.idleInhibitedBy })

            // idleInhibitors = false: programs' idle-inhibit is ignored.
            caulis.onLoop { caulis.idleInhibitors = false }
            assertFalse("mpv" in caulis.onLoop { caulis.idleInhibitedBy })
        }
    }

    @Test
    fun `an on-screen keyboard's labels follow the layout, and Caps Lock is told`() = with(harness) {
        withCaulis({ keyboardLayouts = "us,ru" }) { caulis, _ ->
            assertEquals("q", caulis.onLoop { caulis.keyText(16) })
            assertEquals("Q", caulis.onLoop { caulis.keyText(16, shift = true) })
            assertEquals("й", caulis.onLoop { caulis.keyText(16, layout = 1) })
            caulis.onLoop { caulis.switchKeyboardLayout(1) }
            assertEquals("й", caulis.onLoop { caulis.keyText(16) })
            assertFalse(caulis.onLoop { caulis.capsLockOn })
            caulis.onLoop { r -> r.key(0, 58, true); r.key(0, 58, false) } // Caps Lock
            assertTrue(caulis.onLoop { caulis.capsLockOn })
        }
    }
}
