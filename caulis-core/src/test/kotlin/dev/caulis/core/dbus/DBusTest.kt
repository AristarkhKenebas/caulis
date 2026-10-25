package dev.caulis.core.dbus

import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The D-Bus client against the real system bus: only questions, and a sleep lock let go at once. */
class DBusTest {

    @Test
    fun `asks the bus and logind, and holds a sleep delay lock`() {
        EventLoop().use { loop ->
            val bus = DBusConnection.system(loop)
            assumeTrue(bus != null, "no system bus")
            bus!!.use {
                var id: String? = null
                var canSuspend: String? = null
                var session: ObjectPath? = null
                var lock: Fd? = null
                var problem: String? = null
                bus.call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "GetId") { id = it.body.firstOrNull() as String? }
                bus.call(LOGIN, ObjectPath(MANAGER_PATH), MANAGER, "CanSuspend") { canSuspend = it.body.firstOrNull() as String? }
                bus.call(LOGIN, ObjectPath(MANAGER_PATH), MANAGER, "Inhibit", "ssss", listOf("sleep", "Caulis test", "testing", "delay")) { reply ->
                    if (reply.isError) problem = "${reply.errorName}: ${reply.body}" else (reply.body.firstOrNull() as Fd?)?.let { fd -> bus.keep(fd); lock = fd }
                }
                bus.call(LOGIN, ObjectPath(MANAGER_PATH), MANAGER, "GetSessionByPID", "u", listOf(ProcessHandle.current().pid().toInt())) { reply ->
                    if (!reply.isError) session = reply.body.firstOrNull() as ObjectPath? // (the test runner may be in no session)
                }
                val until = System.nanoTime() + 5_000_000_000
                while ((id == null || canSuspend == null || (lock == null && problem == null)) && System.nanoTime() < until) loop.dispatch(50)
                assertEquals(32, id?.length, "the bus's id: $id")
                assertTrue(canSuspend in setOf("yes", "no", "challenge", "na"), "$canSuspend")
                assertNotNull(lock, "a delay lock: $problem")
                lock!!.close()
                println("session: $session")
            }
        }
    }

    private companion object {
        const val LOGIN = "org.freedesktop.login1"
        const val MANAGER = "org.freedesktop.login1.Manager"
        const val MANAGER_PATH = "/org/freedesktop/login1"
    }
}
