package dev.caulis.wm

import dev.caulis.core.TitleBars
import dev.caulis.protocol.client.xdgshell.XdgToplevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rule { }: where windows open and how they look, as a user writes it. */
class WindowRuleTest {

    private val harness = CaulisTest()

    @Test
    fun `a window opens on its workspace without taking the keyboard, another floats in the middle`() = with(harness) {
        withCaulis({
            rule { appId("music"); workspace = 3 }
            rule { appId("mixer"); floating = true; size(300, 200); center() }
        }) { caulis, client ->
            val first = client.window { it.setAppId("terminal") }.also { it.draw() }
            client.waitFor { caulis.onLoop { it.desktop.focusedWindow?.appId } == "terminal" }

            client.window { it.setAppId("music") }.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.any { it.appId == "music" } } }
            caulis.onLoop { r ->
                val music = r.desktop.allWindows.single { it.appId == "music" }
                assertEquals(3, r.desktop.workspaceNumberOf(music))
                assertEquals(1, r.desktop.activeWorkspace.number, "you stay where you are")
                assertEquals("terminal", r.desktop.focusedWindow?.appId, "the keyboard stays too")
            }

            val mixer = client.window { it.setAppId("mixer") }
            assertEquals(300 to 200, mixer.width to mixer.height, "its size in the very first configure")
            assertFalse(XdgToplevel.State.TILED_LEFT in mixer.states, "not tiled")
            mixer.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.any { it.appId == "mixer" && it.mapped } } }
            caulis.onLoop { r ->
                val window = r.desktop.allWindows.single { it.appId == "mixer" }
                assertTrue(r.desktop.isFloating(window))
                val area = r.desktop.monitors[0].usableArea
                assertEquals(area.x + (area.width - 300) / 2, window.x, "in the middle")
            }
            first.draw()
        }
    }

    @Test
    fun `a window that fits only after changing its title gets the rule then, once`() = with(harness) {
        withCaulis({
            rule { title("Spotify Premium"); workspace = 9 }
            rule { program("java"); titleBars = TitleBars.COMPOSITOR } // the test's own windows
        }) { caulis, client ->
            val window = client.window { it.setTitle("Loading…") }.also { it.draw() }
            client.waitFor { caulis.onLoop { it.desktop.allWindows.size } == 1 }
            assertEquals(1, caulis.onLoop { r -> r.desktop.workspaceNumberOf(r.desktop.allWindows.single()) })

            window.toplevel.setTitle("Spotify Premium")
            client.waitFor { caulis.onLoop { r -> r.desktop.workspaceNumberOf(r.desktop.allWindows.single()) } == 9 }
            // Moved back by hand: a later title change does not move it again.
            caulis.onLoop { r -> r.desktop.moveToWorkspace(r.desktop.allWindows.single(), 1) }
            window.toplevel.setTitle("Spotify Premium — a song")
            client.connection.roundtrip()
            assertEquals(1, caulis.onLoop { r -> r.desktop.workspaceNumberOf(r.desktop.allWindows.single()) })
            assertEquals(TitleBars.COMPOSITOR, caulis.onLoop { r -> r.desktop.titleBarsOf(r.desktop.allWindows.single()) })
        }
    }

    @Test
    fun `after a reload, new rules leave open windows where they are`() = with(harness) {
        withCaulis({}) { caulis, client ->
            client.window { it.setAppId("editor") }.draw()
            client.waitFor { caulis.onLoop { it.desktop.allWindows.size } == 1 }
            val next = caulis.reloaded { rule { appId("editor"); workspace = 4 } }
            caulis.onLoop { it.reconfigure(next) }
            assertEquals(1, next.onLoop { r -> r.desktop.workspaceNumberOf(r.desktop.allWindows.single()) }, "the open one stays")
            client.window { it.setAppId("editor") }.draw()
            client.waitFor { next.onLoop { r -> r.desktop.allWindows.size } == 2 }
            assertEquals(listOf(1, 4), next.onLoop { r -> r.desktop.allWindows.map { r.desktop.workspaceNumberOf(it) }.sortedBy { it } })
        }
    }

    @Test
    fun `margins around one program's windows, each side on its own, negative into the gaps`() = with(harness) {
        withCaulis({
            layout = dev.caulis.tiling.Columns(dev.caulis.tiling.Gaps(inner = 10, outer = 10))
            rule { appId("roomy"); margin(20, 0, 0, 30) }
            rule { appId("video"); margin(-50) }
        }) { caulis, client ->
            client.window { it.setAppId("roomy") }.draw()
            client.window { it.setAppId("video") }.draw()
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 2 }
            caulis.onLoop { r ->
                val area = r.desktop.monitors[0].usableArea
                val roomy = r.desktop.windows.single { it.appId == "roomy" }
                val video = r.desktop.windows.single { it.appId == "video" }
                assertEquals(area.x + 10 + 30, roomy.x, "10 of the gap and 30 of its own on the left")
                assertEquals(area.y + 10 + 20, roomy.y, "and 20 at the top")
                assertEquals(area.right, video.x + video.visibleRect.width, "into the gap up to the edge, not past it")
                assertEquals(area.y, video.y)
            }
        }
    }

    @Test
    fun `rules say plainly what is wrong`() {
        val wrong = runCatching { Caulis().rule { workspace = 0 } }.exceptionOrNull()
        assertEquals("workspace numbers start at 1, not 0", wrong?.message)
        assertNull(runCatching { Caulis().rule { appId("a"); size(10, 10) } }.exceptionOrNull())
        assertEquals("the left margin is -500 to 500, not 900", runCatching { Caulis().rule { margin(0, 0, 0, 900) } }.exceptionOrNull()?.message)
    }
}
