package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.tiling.BinaryTree
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A changed configuration taken on while running: new keys and events, windows stay. */
class ReloadTest {

    private val harness = CaulisTest()

    @Test
    fun `the new keys and events run, windows stay, restart-only settings are named`() = with(harness) {
        val pressed = java.util.concurrent.CopyOnWriteArrayList<String>()
        withCaulis({
            bind("Super+X") { pressed += "old" }
            onWindowOpened { pressed += "old opened" }
        }) { caulis, client ->
            client.window().draw()
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }

            val next = caulis.reloaded {
                bind("Super+X") { pressed += "new" }
                onWindowOpened { pressed += "new opened" }
                onReload { pressed += "reloaded" }
                keyboardLayouts = "us,ru"
                layout = Dwindle(Gaps(0, 0))
            }
            val (restartOnly, _) = caulis.onLoop { it.reconfigure(next) }
            assertEquals(listOf("keyboardLayouts"), restartOnly)
            assertSame(next, next.onLoop { it.config })
            assertEquals(1, next.onLoop { it.desktop.windows.size }, "the window is still there")

            next.onLoop { r -> r.key(0, SUPER, true); r.key(0, X, true); r.key(0, X, false); r.key(0, SUPER, false) }
            client.window().draw()
            client.waitFor { next.onLoop { it.desktop.windows.size } == 2 }
            assertEquals(listOf("old opened", "reloaded", "new", "new opened"), pressed.toList())
        }
    }

    @Test
    fun `a layout that remembers the arrangement is kept, other changes come`() = with(harness) {
        val tree = BinaryTree()
        withCaulis({ layout = tree }) { caulis, _ ->
            val next = caulis.reloaded { layout = BinaryTree(Gaps(20, 20)) }
            val (_, notes) = caulis.onLoop { it.reconfigure(next) }
            assertSame(tree, next.onLoop { it.desktop.layout }, "the same tree, with its arrangement")
            assertTrue(notes.single().contains("keeps your arrangement"))
            val third = caulis.reloaded { layout = Dwindle() }
            next.onLoop { it.reconfigure(third) }
            assertEquals("Dwindle", third.onLoop { it.desktop.layout.name })
        }
    }

    @Test
    fun `old warnings about the configuration go, new ones come`() = with(harness) {
        withCaulis({ keys { close = "Super+F" } }) { caulis, client ->
            client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { it.level == NoticeLevel.WARNING } } }
            val fixed = caulis.reloaded {} // the mistake is fixed
            caulis.onLoop { it.reconfigure(fixed) }
            client.waitFor { fixed.onLoop { r -> r.desktop.notices.none { it.level == NoticeLevel.WARNING } } }
        }
    }

    @Test
    fun `while a reload runs main, caulis { } hands its configuration over instead of starting`() {
        val slot = arrayOfNulls<Caulis>(1)
        Reloader.capturing.set(slot)
        try {
            caulis { title = "handed over" }
            error("caulis { } should not return normally")
        } catch (_: Reloader.Captured) {
        } finally {
            Reloader.capturing.remove()
        }
        assertEquals("handed over", slot[0]?.title)
    }

    private companion object {
        const val SUPER = 125
        const val X = 45
    }
}
