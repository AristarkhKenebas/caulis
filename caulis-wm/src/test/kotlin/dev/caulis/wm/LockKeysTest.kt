package dev.caulis.wm

import dev.caulis.core.input.Gesture
import dev.caulis.core.input.KeyBindings.Modifier
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** While the screen is locked, the compositor's keys and gestures are off (except quit and consoles). */
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

    private companion object {
        const val KEY_SUPER = 125
        const val KEY_Q = 16
    }
}
