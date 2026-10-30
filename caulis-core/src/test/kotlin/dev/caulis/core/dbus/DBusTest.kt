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
                lock.close()
                println("session: $session")
            }
        }
    }

    @Test
    fun `dicts are written, descriptors belong to their own message`() {
        EventLoop().use { loop ->
            val server = DBusConnection.session(loop)
            assumeTrue(server != null, "no session bus")
            val client = DBusConnection.session(loop)!!
            server!!.use { client.use {
                fun until(done: () -> Boolean) {
                    val end = System.nanoTime() + 5_000_000_000
                    while (!done() && System.nanoTime() < end) loop.dispatch(20)
                    check(done()) { "timed out" }
                }
                until { server.uniqueName != null && client.uniqueName != null }
                server.export("/dev/caulis/Test", "dev.caulis.Test", mapOf(
                    "Props" to DBusMethod("", "a{sv}") { listOf(mapOf("Volume" to Variant("i", 40), "Name" to Variant("s", "x"))) },
                    "Ignore" to DBusMethod("", "") { emptyList() },
                    "Write" to DBusMethod("h", "") { call ->
                        java.lang.foreign.Arena.ofConfined().use { arena -> (call.body[0] as Fd).write(arena.allocate(1)) }
                        emptyList()
                    },
                ))
                val name = server.uniqueName!!
                var props: Map<Any?, Any?>? = null
                @Suppress("UNCHECKED_CAST")
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Props") { reply ->
                    props = (reply.body.firstOrNull() as List<List<Any?>>?)?.associate { it[0] to (it[1] as Variant).value }
                }
                // A descriptor sent along with no "h" for it is not handed to the next call.
                val (stray, strayWrite) = Fd.pipe()
                val (read, write) = Fd.pipe()
                var done = 0
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Ignore", fds = listOf(strayWrite)) { done++ }
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Write", "h", listOf(0), fds = listOf(write)) { done++ }
                strayWrite.close()
                write.close()
                until { props != null && done == 2 }
                assertEquals(mapOf<Any?, Any?>("Volume" to 40, "Name" to "x"), props)
                read.use { stray.use {
                    read.setNonBlocking()
                    java.lang.foreign.Arena.ofConfined().use { arena -> assertEquals(1L, read.read(arena.allocate(8)), "written into its own descriptor") }
                } }
            } }
        }
    }

    @Test
    fun `an exported object answers calls, says what it has, and sends signals`() {
        EventLoop().use { loop ->
            val server = DBusConnection.session(loop)
            assumeTrue(server != null, "no session bus")
            val client = DBusConnection.session(loop)!!
            server!!.use { client.use {
                fun until(done: () -> Boolean) {
                    val end = System.nanoTime() + 5_000_000_000
                    while (!done() && System.nanoTime() < end) loop.dispatch(20)
                    check(done()) { "timed out" }
                }
                until { server.uniqueName != null && client.uniqueName != null }
                server.export("/dev/caulis/Test", "dev.caulis.Test", mapOf(
                    "Add" to DBusMethod("ii", "i") { call -> listOf((call.body[0] as Int) + (call.body[1] as Int)) },
                    "Fail" to DBusMethod("", "") { throw DBusError("dev.caulis.Test.Error.No", "not today") },
                ))
                val name = server.uniqueName!!
                var sum: Int? = null
                var error: String? = null
                var missing: String? = null
                var xml: String? = null
                var signal: List<Any?>? = null
                client.onSignal("dev.caulis.Test", "Changed") { signal = it.body }
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Add", "ii", listOf(2, 40)) { sum = it.body.firstOrNull() as Int? }
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Fail") { error = it.errorName }
                client.call(name, ObjectPath("/dev/caulis/Test"), "dev.caulis.Test", "Nothing") { missing = it.errorName }
                client.call(name, ObjectPath("/dev/caulis/Test"), "org.freedesktop.DBus.Introspectable", "Introspect") { xml = it.body.firstOrNull() as String? }
                until { sum != null && error != null && missing != null && xml != null }
                assertEquals(42, sum)
                assertEquals("dev.caulis.Test.Error.No", error)
                assertEquals("org.freedesktop.DBus.Error.UnknownMethod", missing)
                assertTrue("<method name=\"Add\"><arg direction=\"in\" type=\"i\"/>" in xml!!, xml)
                Thread.sleep(200) // (the match rule reaches the bus first)
                loop.dispatch(50)
                server.emitSignal("/dev/caulis/Test", "dev.caulis.Test", "Changed", "s", listOf("hello"))
                until { signal != null }
                assertEquals(listOf<Any?>("hello"), signal)
            } }
        }
    }

    @Test
    fun `a signal from someone else than the sender asked for is not heard`() {
        EventLoop().use { loop ->
            val server = DBusConnection.session(loop)
            assumeTrue(server != null, "no session bus")
            val client = DBusConnection.session(loop)!!
            val forger = DBusConnection.session(loop)!!
            server!!.use { client.use { forger.use {
                fun until(done: () -> Boolean) {
                    val end = System.nanoTime() + 5_000_000_000
                    while (!done() && System.nanoTime() < end) loop.dispatch(20)
                    check(done()) { "timed out" }
                }
                until { server.uniqueName != null && client.uniqueName != null && forger.uniqueName != null }
                val name = "dev.caulis.SenderTest.p${ProcessHandle.current().pid()}"
                var owned: Boolean? = null
                server.requestName(name) { owned = it }
                until { owned != null }
                assumeTrue(owned == true, "the name $name could not be had")
                val heard = ArrayList<Any?>()
                val any = ArrayList<Any?>()
                client.onSignal("dev.caulis.SenderTest", "Changed", sender = name) { heard += it.body.firstOrNull() }
                client.onSignal("dev.caulis.SenderTest", "Changed") { any += it.body.firstOrNull() }
                Thread.sleep(200) // (the match rules and the owner's name reach us first)
                loop.dispatch(50)
                forger.emitSignal("/dev/caulis/Test", "dev.caulis.SenderTest", "Changed", "s", listOf("forged"))
                server.emitSignal("/dev/caulis/Test", "dev.caulis.SenderTest", "Changed", "s", listOf("real"))
                until { "real" in heard && any.size == 2 }
                Thread.sleep(100)
                loop.dispatch(20)
                assertEquals(listOf<Any?>("real"), heard, "only the owner of $name")
            } } }
        }
    }

    private companion object {
        const val LOGIN = "org.freedesktop.login1"
        const val MANAGER = "org.freedesktop.login1.Manager"
        const val MANAGER_PATH = "/org/freedesktop/login1"
    }
}
