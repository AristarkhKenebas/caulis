package dev.caulis.wm

import dev.caulis.ipc.CaulisIpc
import dev.caulis.ipc.v1.EventKind
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Your own keys: modes, the mouse, the wheel, repeating; and the focus following the pointer. */
class InputTest {

    private val harness = CaulisTest()

    @Test
    fun `key modes, mouse buttons, the wheel and repeating keys`() = with(harness) {
        val inMode = AtomicInteger()
        val side = AtomicInteger()
        val louder = AtomicInteger()
        withCaulis({
            configName = "inputtest"
            repeatDelay = 150
            repeatRate = 20
            bind("Super+R") { enterMode("resize") }
            mode("resize") {
                bind("H") { inMode.incrementAndGet() }
                bind("Escape") { enterMode(null) }
            }
            bind("MouseSide") { side.incrementAndGet() }
            bind("Super+WheelDown") { run("workspace 3") }
            bind("XF86AudioRaiseVolume", BindOptions(repeat = true)) { louder.incrementAndGet() }
        }) { caulis, _ ->
            val socket = caulis.socketDirectory!!.resolve("inputtest-${caulis.onLoop { it.socket }}.sock")
            CaulisIpc.connect(socket).use { ipc ->
                ipc.subscribe(EventKind.EVENT_KIND_KEYS)
                fun tap(vararg codes: Int) = caulis.onLoop { r ->
                    codes.forEach { r.key(0, it, true) }
                    codes.reversed().forEach { r.key(0, it, false) }
                }
                tap(SUPER, R)
                assertEquals("resize", ipc.nextEvent()!!.keyModeChanged)
                assertEquals("resize", ipc.state().keyMode)
                tap(H)
                tap(SUPER, R) // the usual keys are off in the mode
                assertEquals(1, inMode.get())
                tap(ESC)
                assertEquals("", ipc.nextEvent()!!.keyModeChanged)
                assertNull(caulis.keyMode)
            }

            caulis.onLoop { r ->
                r.pointerButton(0, 0x113, true)
                r.pointerButton(0, 0x113, false)
                r.key(0, SUPER, true)
                r.pointerAxis(0, true, 15.0, 120)
                r.key(0, SUPER, false)
            }
            assertEquals(1, side.get())
            assertEquals(3, caulis.onLoop { it.desktop.activeWorkspace.number }, "Super+WheelDown")

            caulis.onLoop { r -> r.key(0, VOLUME_UP, true) }
            Thread.sleep(400) // 150 ms, then every 50 ms
            caulis.onLoop { r -> r.key(0, VOLUME_UP, false) }
            val count = louder.get()
            assertTrue(count in 4..9, "repeated while held: $count")
            Thread.sleep(150)
            assertEquals(count, louder.get(), "and stopped when let go")
        }
    }

    @Test
    fun `the focus follows the pointer`() = with(harness) {
        withCaulis({
            focusFollowsMouse = true
            layout = dev.caulis.tiling.Columns()
        }) { caulis, client ->
            client.window { it.setAppId("left") }.draw()
            client.window { it.setAppId("right") }.draw()
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.size == 2 && r.desktop.focusedWindow?.appId == "right" } }
            val left = caulis.onLoop { r -> r.desktop.windows.single { it.appId == "left" }.let { it.x + 20.0 to it.y + 40.0 } }
            caulis.onLoop { r -> r.desktop.pointerMoved(0, left.first, left.second) }
            assertEquals("left", caulis.onLoop { r -> r.desktop.focusedWindow?.appId })
        }
    }

    private companion object {
        const val SUPER = 125
        const val R = 19
        const val H = 35
        const val ESC = 1
        const val VOLUME_UP = 115
    }
}
