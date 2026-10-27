package dev.caulis.wm

import dev.caulis.core.input.Gesture
import dev.caulis.core.input.KeyBindings.Modifier
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** While the screen is locked, the compositor's keys and gestures are off (except consoles, and quit with keys.quitWhileLocked). */
class LockKeysTest {

    private val harness = CaulisTest()

    @Test
    fun `no shortcuts while locked, and no stuck Super key afterwards`() = with(harness) {
        withCaulis({}) { caulis, client ->
            client.window().draw()
            client.waitFor { caulis.onLoop { it.desktop.windows.size } == 1 }

            // Super goes down before the lock (as with Super+L) and comes up while locked.
            caulis.onLoop { it.key(0, KEY_SUPER, true) }
            var locked = false
            val lock = client.sessionLock!!.lock()
            lock.events = object : ExtSessionLockV1.Events {
                override fun locked(proxy: ExtSessionLockV1) {
                    locked = true
                }
            }
            client.waitFor { locked }

            caulis.onLoop { r ->
                r.key(0, KEY_Q, true) // Super+Q: would close the window
                r.key(0, KEY_Q, false)
                r.key(0, KEY_SUPER, false)
                r.gesture(Gesture.PinchBegin(0, 4)) // would open the overview
                r.gesture(Gesture.PinchUpdate(0, 0.0, 0.0, 0.3, 0.0))
                r.gesture(Gesture.PinchEnd(0, cancelled = false))
            }
            assertEquals(1, caulis.onLoop { it.desktop.windows.size }, "the window is still open")
            assertFalse(caulis.onLoop { it.desktop.inOverview }, "no overview behind the lock")

            lock.unlockAndDestroy()
            client.waitFor { !caulis.onLoop { it.desktop.locked } }
            assertFalse(caulis.onLoop { it.keys.isHeld(Modifier.SUPER) }, "Super is not stuck")
        }
    }

    @Test
    fun `the quit key does nothing on a locked screen unless quitWhileLocked`() = with(harness) {
        for (allowed in listOf(false, true)) {
            withCaulis({ keys { quitWhileLocked = allowed } }) { caulis, client ->
                var locked = false
                val lock = client.sessionLock!!.lock()
                lock.events = object : ExtSessionLockV1.Events {
                    override fun locked(proxy: ExtSessionLockV1) {
                        locked = true
                    }
                }
                client.waitFor { locked }
                val bound = caulis.onLoop { r ->
                    r.key(0, KEY_CTRL, true)
                    r.key(0, KEY_ALT, true)
                    r.activeKeys().has(KEY_BACKSPACE).also {
                        r.key(0, KEY_ALT, false)
                        r.key(0, KEY_CTRL, false)
                    }
                }
                assertEquals(allowed, bound, "Ctrl+Alt+BackSpace quits on a locked screen: $bound (quitWhileLocked = $allowed)")
                lock.unlockAndDestroy()
            }
        }
    }

    @Test
    fun `a locker that does not show brings the compositor's own lock, ended only by the locker it starts`() = with(harness) {
        withCaulis({
            lockCommand = "surely-no-such-locker-here"
            lockShowMillis = 200
        }) { caulis, client ->
            caulis.onLoop { it.config.lock() }
            client.waitFor { caulis.onLoop { it.desktop.lockedInternally } }
            fun lockAndUnlock() {
                var locked = false
                val lock = client.sessionLock!!.lock()
                lock.events = object : ExtSessionLockV1.Events {
                    override fun locked(proxy: ExtSessionLockV1) {
                        locked = true
                    }
                }
                client.waitFor { locked }
                lock.unlockAndDestroy()
                var rounds = 0
                client.waitFor { rounds++ >= 2 } // (the unlock arrived)
            }
            // Any program could lock and unlock at once, without a password: our lock stays.
            lockAndUnlock()
            Thread.sleep(100)
            assertTrue(caulis.onLoop { it.desktop.lockedInternally && it.desktop.locked }, "a stranger's unlock does not end it")
            // The locker the compositor started (its pid: here the test's own process) ends it.
            caulis.onLoop { it.lockerPids += ProcessHandle.current().pid() }
            lockAndUnlock()
            client.waitFor { !caulis.onLoop { it.desktop.locked } }
        }
        withCaulis({
            lockCommand = "surely-no-such-locker-here"
            lockShowMillis = 200
            lockFailure = LockFailure.NOTICE
        }) { caulis, _ ->
            caulis.onLoop { it.config.lock() }
            Thread.sleep(600)
            assertFalse(caulis.onLoop { it.desktop.locked }, "only a notice")
        }
    }

    private companion object {
        const val KEY_SUPER = 125
        const val KEY_Q = 16
        const val KEY_CTRL = 29
        const val KEY_ALT = 56
        const val KEY_BACKSPACE = 14
    }
}
