package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The compositor's own lock screen (Desktop.lockInternally) locks as a locker program does,
 * and focus moves only as the rule against focus stealing says.
 */
class InternalLockTest {

    // A widget covering [rect]: what it got.
    private open class Panel(val rect: Rect, val onLock: Boolean = false) : Widget {
        val buttons = ArrayList<Boolean>()
        var keys = 0
        var focused = false

        override fun area(monitor: Monitor) = rect
        override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) = canvas.fill(area, 0f, 0f, 1f)
        override fun pointerButton(button: Int, pressed: Boolean) { buttons += pressed }
        override val takesKeyboard get() = true
        override fun key(key: WidgetKey) { if (key.pressed) keys++ }
        override fun keyboardFocus(has: Boolean) { focused = has }
        override val showsWhileLocked get() = onLock
    }

    @Test
    fun `the compositor's own lock screen gets all input and no window takes the focus`() = withServer { server, client ->
        val window = client.window()
        window.draw()
        client.waitFor { server.onServer { d -> d.keyboardFocus?.roleObject is Window } }
        val screen = Panel(Rect(0, 0, 200, 200))
        val bar = Panel(Rect(300, 0, 100, 30))
        val keyboard = Panel(Rect(300, 100, 100, 30), onLock = true)
        val heard = ArrayList<Boolean>()
        val before = server.onServer { d -> d.focusedWindow }
        server.onServer { d ->
            d.onLockChanged { heard += it }
            d.addWidget(bar)
            d.addWidget(keyboard)
            d.lockInternally(screen)
            assertTrue(d.locked)
            assertTrue(d.lockedInternally)
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus, "no program has the keyboard")
            d.key(0, KEY_A, true)
            d.key(0, KEY_A, false)
            assertEquals(1, screen.keys)
            // No window takes the focus, however asked.
            d.focus(d.windows.first())
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus)
            // A widget not marked for the lock screen gets nothing, one marked does.
            d.pointerMoved(0, 350.0, 10.0)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertTrue(bar.buttons.isEmpty())
            d.focusWidget(bar)
            assertSame(screen, d.keyboardWidget)
            d.pointerMoved(0, 350.0, 110.0)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertEquals(listOf(true, false), keyboard.buttons)
            assertSame(keyboard, d.keyboardWidget)
            // Done with it: the keyboard goes back to the lock screen, not to a program.
            d.focusWidget(null)
            assertSame(screen, d.keyboardWidget)
            // A window clicked behind the lock gets nothing.
            d.pointerMoved(0, 600.0, 400.0)
            assertNull(d.pointerWindow)
        }
        // A new window does not take the focus either.
        client.window().draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        server.onServer { d ->
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus)
            d.unlockInternally()
            assertFalse(d.locked)
            assertSame(before?.surface, d.keyboardFocus, "the window focused before the lock has the keyboard again, not the new one")
            assertFalse(screen.focused)
            assertEquals(listOf(true, false), heard)
        }
    }

    @Test
    fun `a window on a hidden workspace going fullscreen does not take the keyboard`() = withServer { server, client ->
        val victim = client.window()
        victim.draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        // (Another program: one may move the focus among its own windows.)
        val thiefClient = TestClient(Connection.connect(server.socketPath))
        val thief = thiefClient.window()
        thief.draw()
        thiefClient.waitFor { server.onServer { d -> d.windows.size } == 2 }
        val (victimWindow, thiefWindow) = server.onServer { d ->
            val thiefWindow = d.windows.last()
            d.moveToWorkspace(thiefWindow, 2)
            d.focus(d.windows.single())
            d.windows.single() to thiefWindow
        }
        thief.toplevel.setFullscreen(null)
        thiefClient.waitFor { server.onServer { thiefWindow.fullscreen } }
        server.onServer { d ->
            assertSame(victimWindow, d.focusedWindow)
            assertSame(victimWindow.surface, d.keyboardFocus)
            assertTrue(thiefWindow.urgent, "marked urgent instead")
        }
        thiefClient.close()
    }

    @Test
    fun `the focused window closing while a widget has the keyboard leaves the focus to the next one`() = withServer { server, client ->
        val other = client.window()
        other.draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        val focused = client.window()
        focused.draw()
        client.waitFor { server.onServer { d -> d.windows.size == 2 && d.keyboardFocus === d.windows.last().surface } }
        val launcher = Panel(Rect(0, 0, 100, 100))
        server.onServer { d ->
            d.addWidget(launcher)
            d.focusWidget(launcher)
        }
        focused.toplevel.destroy()
        focused.xdgSurface.destroy()
        focused.surface.destroy()
        client.waitFor { server.onServer { d -> d.windows.size } == 1 }
        server.onServer { d ->
            assertSame(launcher, d.keyboardWidget, "the launcher keeps the keyboard")
            assertSame(d.windows.single(), d.focusedWindow)
            d.focusWidget(null)
            assertSame(d.windows.single().surface, d.keyboardFocus)
        }
    }

    @Test
    fun `idle inhibitors and keep-awake windows do not count while locked`() = withServer { server, client ->
        client.window().draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.mapped } == true }
        server.onServer { d ->
            val idle = Idle(server.display, server.loop, d)
            d.behaviorFor = { WindowBehavior(keepsAwake = true) }
            assertTrue(idle.inhibited())
            d.lockInternally(Panel(Rect(0, 0, 10, 10)))
            assertFalse(idle.inhibited(), "not while locked")
            idle.inhibitorsWorkWhileLocked = true
            assertTrue(idle.inhibited())
            d.unlockInternally()
        }
    }

    @Test
    fun `after unlocking the window focused before gets the keyboard, never one that opened meanwhile`() = withServer { server, client ->
        client.window { it.setTitle("a") }.draw()
        client.window { it.setTitle("b") }.draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 2 }
        val screen = Panel(Rect(0, 0, 100, 100))
        server.onServer { d ->
            d.focus(d.windows.single { it.title == "a" })
            d.lockInternally(screen)
        }
        client.window { it.setTitle("came while locked") }.draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 3 }
        server.onServer { d ->
            d.unlockInternally()
            assertEquals("a", d.focusedWindow?.title, "the window focused before the lock")
            assertSame(d.focusedWindow?.surface, d.keyboardFocus)
        }
        // The other setting: the newest window of those open before the lock.
        server.onServer { d ->
            d.unlockFocus = UnlockFocus.NEWEST_BEFORE
            d.focus(d.windows.single { it.title == "a" })
            d.lockInternally(screen)
        }
        val too = client.window { it.setTitle("came while locked too") }.also { it.draw() }
        client.waitFor { server.onServer { d -> d.windows.size } == 4 }
        server.onServer { d ->
            d.unlockInternally()
            assertEquals("came while locked", d.focusedWindow?.title, "the newest from before this lock")
        }
        // The window focused before closed meanwhile: the newest from before, not the new one.
        server.onServer { d ->
            d.unlockFocus = UnlockFocus.FOCUSED_BEFORE
            d.focus(d.windows.single { it.title == "came while locked too" })
            d.lockInternally(screen)
        }
        client.window { it.setTitle("third time") }.draw()
        client.waitFor { server.onServer { d -> d.windows.size } == 5 }
        too.toplevel.destroy()
        too.xdgSurface.destroy()
        too.surface.destroy()
        client.waitFor { server.onServer { d -> d.windows.size } == 4 }
        server.onServer { d ->
            d.unlockInternally()
            assertEquals("came while locked", d.focusedWindow?.title, "the newest from before the lock, not the one that opened while locked")
        }
    }

    @Test
    fun `a surface the lock screen allows gets the keyboard when clicked`() = withServer { server, client ->
        client.window().draw()
        client.waitFor { server.onServer { d -> d.keyboardFocus?.roleObject is Window && d.screenRect(d.windows.single()) != null } }
        val screen = Panel(Rect(0, 0, 50, 50))
        server.onServer { d ->
            val window = d.windows.single()
            d.lockInternally(screen, allowSurface = { it === window.surface })
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus)
            val rect = d.screenRect(window)!!
            d.pointerMoved(0, rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            assertSame(window, d.pointerWindow, "the allowed window gets the pointer")
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertSame(window.surface, d.keyboardFocus, "and the keyboard, clicked")
            assertNull(d.keyboardWidget)
            assertTrue(d.locked)
            // A click on the lock screen takes it back.
            d.pointerMoved(0, 10.0, 10.0)
            d.pointerButton(0, BTN_LEFT, true)
            d.pointerButton(0, BTN_LEFT, false)
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus)
            d.unlockInternally()
        }
    }

    @Test
    fun `hiding the desktop shows only the lock colour and the lock screen`() = withServer { server, client ->
        client.window().draw()
        client.waitFor { server.onServer { d -> d.windows.singleOrNull()?.mapped } == true }
        val width = ScreenTestServer.WIDTH
        val height = ScreenTestServer.HEIGHT
        val corner = { server.screenshot().let { it[(height - 1) * width + width - 1] } }
        val before = corner()
        val screen = Panel(Rect(0, 0, 20, 20))
        server.onServer { d ->
            d.lockColor = dev.caulis.render.Color(1f, 0f, 0f)
            d.lockInternally(screen, hideDesktop = true)
        }
        client.waitFor { corner() == RED }
        assertEquals(BLUE, server.screenshot()[10 * width + 10], "the lock screen is drawn")
        server.onServer { d -> d.unlockInternally() }
        client.waitFor { corner() == before }
    }

    @Test
    fun `panels behind the own lock screen are left out unless panelsShowWhileLocked`() = withServer { server, client ->
        val width = ScreenTestServer.WIDTH
        // A notification daemon's pop-up: black, 40 x 40, in the top right corner (the overlay layer).
        val surface = client.compositor.createSurface()
        val layer = client.layerShell!!.getLayerSurface(surface, null, 3, "notifications")
        layer.events = object : dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1.Events {
            override fun configure(proxy: dev.caulis.protocol.client.wlrlayershellunstablev1.ZwlrLayerSurfaceV1, serial: Int, width: Int, height: Int) = proxy.ackConfigure(serial)
        }
        layer.setSize(40, 40)
        layer.setAnchor(1 or 8) // top, right
        surface.commit()
        client.connection.roundtrip()
        client.draw(surface, 40, 40)
        val popup = { server.screenshot()[20 * width + width - 20] }
        val black = 0xff000000.toInt()
        client.waitFor { popup() == black }
        val screen = Panel(Rect(0, 0, 20, 20))
        // By default the desktop behind the own lock screen leaves its panels out.
        server.onServer { d -> d.lockInternally(screen) }
        client.waitFor { popup() != black }
        // Turned on: drawn behind the lock screen, off again: hidden.
        server.onServer { d -> d.panelsShowWhileLocked = true }
        client.waitFor { popup() == black }
        server.onServer { d -> d.panelsShowWhileLocked = false }
        client.waitFor { popup() != black }
        // One the lock allows is drawn (a lock screen that is a panel program).
        server.onServer { d -> d.lockInternally(screen, allowSurface = { it.roleObject is LayerSurface }) }
        client.waitFor { popup() == black }
        server.onServer { d -> d.lockInternally(screen) }
        client.waitFor { popup() != black }
        // Unlocked: there again.
        server.onServer { d -> d.unlockInternally() }
        client.waitFor { popup() == black }
    }

    @Test
    fun `a locker program on top of the own lock screen, unlocking or crashing, leaves the own lock in charge`() = withServer { server, client ->
        client.window().draw()
        client.waitFor { server.onServer { d -> d.keyboardFocus?.roleObject is Window } }
        val screen = Panel(Rect(0, 0, 50, 50))
        server.onServer { d -> d.lockInternally(screen) }
        // swaylock on top, then unlocked: our own lock is still there.
        TestClient(Connection.connect(server.socketPath)).use { locker ->
            val lock = locker.lock()
            locker.waitFor { lock.locked }
            server.onServer { d -> assertTrue(d.keyboardWidget !== screen, "the locker program has the keyboard") }
            lock.lock.unlockAndDestroy()
            locker.connection.roundtrip()
        }
        server.onServer { d ->
            assertTrue(d.locked && d.lockedInternally)
            assertSame(screen, d.keyboardWidget)
            assertNull(d.keyboardFocus)
        }
        // swaylock on top, then crashing: our own lock screen is in charge again (no red screen).
        val crashing = TestClient(Connection.connect(server.socketPath))
        val lock = crashing.lock()
        crashing.waitFor { lock.locked }
        crashing.close()
        client.waitFor { server.onServer { d -> d.keyboardWidget } === screen }
        server.onServer { d ->
            assertTrue(d.locked)
            d.key(0, KEY_A, true)
            d.key(0, KEY_A, false)
            assertEquals(1, screen.keys, "the own lock screen gets the keys again")
            d.unlockInternally()
            assertFalse(d.locked, "and its unlock unlocks")
            assertTrue(d.keyboardFocus?.roleObject is Window)
        }
        // A new locker can lock again afterwards.
        TestClient(Connection.connect(server.socketPath)).use { locker ->
            val again = locker.lock()
            locker.waitFor { again.locked }
            again.lock.unlockAndDestroy()
            locker.waitFor { !server.onServer { d -> d.locked } }
        }
    }

    // A locker program's lock: whether it heard "locked".
    private class Locker(val lock: ExtSessionLockV1) {
        @Volatile var locked = false
    }

    private fun TestClient.lock(): Locker {
        val lock = sessionLock!!.lock()
        val locker = Locker(lock)
        lock.events = object : ExtSessionLockV1.Events {
            override fun locked(proxy: ExtSessionLockV1) {
                locker.locked = true
            }
        }
        return locker
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server -> TestClient(Connection.connect(server.socketPath)).use { test(server, it) } }
    }

    private companion object {
        const val BTN_LEFT = 0x110
        const val KEY_A = 30
        const val RED = 0xffff0000.toInt()
        const val BLUE = 0xff0000ff.toInt()
    }
}
